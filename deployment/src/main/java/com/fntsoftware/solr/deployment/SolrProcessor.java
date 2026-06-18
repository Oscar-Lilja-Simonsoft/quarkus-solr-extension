package com.fntsoftware.solr.deployment;

import com.fntsoftware.solr.runtime.SolrClientProducer;
import com.fntsoftware.solr.runtime.SolrDevserviceConfig;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.CuratedApplicationShutdownBuildItem;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.runtime.LaunchMode;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.dockerfile.statement.MultiArgsStatement;
import java.io.IOException;
import java.util.Map;
import java.util.function.BooleanSupplier;

class SolrProcessor {
    private static final Logger LOG = Logger.getLogger(SolrProcessor.class);

    SolrDevserviceConfig config;
    static volatile DevServicesResultBuildItem.RunningDevService devService;

    private static final String FEATURE = "solr";
    private static final String SOLR_URL = "quarkus.solr.url";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep
    public AdditionalBeanBuildItem producer() {
        return AdditionalBeanBuildItem.builder()
                .addBeanClass(SolrClientProducer.class)
                .setUnremovable()
                .build();
    }

    @BuildStep(onlyIf = WantsSolrDevService.class)
    public DevServicesResultBuildItem createContainer(CuratedApplicationShutdownBuildItem closeBuildItem) {
        LOG.infof("SOLR DEV SERVICE: Preparing Solr container for core '%s'.", config.core());
        if (devService != null) {
            return null;
        }
        Runnable closeTask = () -> {
            if (devService != null) {
                try {
                    devService.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            devService = null;
        };
        closeBuildItem.addCloseTask(closeTask, false);
        ImageFromDockerfile image = new ImageFromDockerfile("quarkus/devservices/solr")
                .withFileFromClasspath(".", config.configPath()).withDockerfileFromBuilder(builder -> {
                    builder.from("solr:" + config.version()).withStatement(
                            new MultiArgsStatement("COPY --chown=solr:solr", ".", "/var/solr/data/" + config.core()));
                });
        SolrContainer container = new SolrContainer(image, config.core());
        container.start();
        Map<String, String> props = Map.of("quarkus.solr.url", "http://" + container.getHost() + ":"
                + container.getMappedPort(container.getPort()) + "/solr/" + config.core());
        devService = new DevServicesResultBuildItem.RunningDevService(FEATURE, container.getContainerId(),
                container::close, props);
        return devService.toBuildItem();
    }

    static class WantsSolrDevService implements BooleanSupplier {
        LaunchMode launchMode;
        SolrDevserviceConfig config;

        public boolean getAsBoolean() {
            Boolean devServicesActive = ConfigProvider.getConfig()
                    .getOptionalValue("quarkus.devservices.enabled", Boolean.class)
                    .orElse(true);
            return launchMode.isDevOrTest() && devServicesActive && config.enabled() && !isSolrUrlConfigured();
        }

        private boolean isSolrUrlConfigured() {
            return ConfigProvider.getConfig().getOptionalValue(SOLR_URL, String.class).isPresent();
        }
    }

    private static class SolrContainer extends GenericContainer<SolrContainer> {
        static final int PORT = 8983;
        private final String core;

        public SolrContainer(ImageFromDockerfile image, String core) {
            super(image);
            this.core = core;
        }

        public int getPort() {
            return PORT;
        }

        @Override
        protected void configure() {
            super.configure();
            addExposedPort(PORT);
            waitingFor(Wait.forHttp("/solr/" + core + "/admin/ping").forPort(PORT).forStatusCode(200));
        }
    }
}
