package com.fntsoftware.solr.deployment;

import com.fntsoftware.solr.runtime.SolrClientProducer;
import com.fntsoftware.solr.runtime.SolrDevserviceConfig;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.IsProduction;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.DevServicesResultBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.Startable;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.dockerfile.statement.MultiArgsStatement;
import java.util.Map;

class SolrProcessor {
    private static final Logger LOG = Logger.getLogger(SolrProcessor.class);

    private static final String FEATURE = "solr";
    private static final String GLOBAL_DEV_SERVICES_ENABLED = "quarkus.devservices.enabled";
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

    @BuildStep(onlyIfNot = IsProduction.class)
    DevServicesResultBuildItem startSolrDevService(SolrDevserviceConfig config) {
        if (!globalDevServicesEnabled()) {
            LOG.info("SOLR DEV SERVICE: Global Dev Services are disabled.");
            return null;
        }

        if (!config.enabled()) {
            LOG.info("SOLR DEV SERVICE: Solr Dev Service is disabled.");
            return null;
        }

        if (isConfigured(SOLR_URL)) {
            LOG.info("SOLR DEV SERVICE: quarkus.solr.url is already configured. Not starting container.");
            return null;
        }

        String core = config.core();
        String version = config.version();
        String configPath = config.configPath();

        LOG.infof("SOLR DEV SERVICE: Preparing Solr container for core '%s'.", core);

        return DevServicesResultBuildItem.owned()
                .feature(FEATURE)
                .description("Solr Dev Service")
                .serviceConfig(new DevServiceConfiguration(core, version, configPath))
                .startable(() -> SolrContainer.create(core, version, configPath))
                .configProvider(Map.of(
                        SOLR_URL, SolrContainer::getConnectionInfo
                ))
                .build();
    }

    private boolean globalDevServicesEnabled() {
        return ConfigProvider.getConfig()
                .getOptionalValue(GLOBAL_DEV_SERVICES_ENABLED, Boolean.class)
                .orElse(true);
    }

    private boolean isConfigured(String configKey) {
        return ConfigProvider.getConfig()
                .getOptionalValue(configKey, String.class)
                .isPresent();
    }

    private record DevServiceConfiguration(
            String core,
            String version,
            String configPath
    ) {
    }

    private static class SolrContainer extends GenericContainer<SolrContainer> implements Startable {
        static final int PORT = 8983;
        private final String core;

        static SolrContainer create(String core, String version, String configPath) {
            ImageFromDockerfile image = new ImageFromDockerfile("quarkus/devservices/solr")
                    .withFileFromClasspath(".", configPath).withDockerfileFromBuilder(builder -> {
                        builder.from("solr:" + version).withStatement(
                                new MultiArgsStatement("COPY --chown=solr:solr", ".", "/var/solr/data/" + core));
                    });
            return new SolrContainer(image, core);
        }

        private SolrContainer(ImageFromDockerfile image, String core) {
            super(image);
            this.core = core;
        }

        @Override
        protected void configure() {
            super.configure();
            addExposedPort(PORT);
            waitingFor(Wait.forHttp("/solr/" + core + "/admin/ping").forPort(PORT).forStatusCode(200));
        }

        @Override
        public String getConnectionInfo() {
            return "http://" + getHost() + ":" + getMappedPort(PORT) + "/solr/" + core;
        }

        @Override
        public void close() {
            stop();
        }
    }
}
