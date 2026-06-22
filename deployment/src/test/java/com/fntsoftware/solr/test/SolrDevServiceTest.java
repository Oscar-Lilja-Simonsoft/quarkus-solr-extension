package com.fntsoftware.solr.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.common.SolrInputDocument;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class SolrDevServiceTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml"))
            .overrideConfigKey("quarkus.solr.devservices.core", "repositem")
            .overrideConfigKey("quarkus.solr.devservices.config-path", "custom-solr");

    @Inject
    SolrClient solrClient;

    @Test
    void shouldStartSolrDevServiceWithCustomConfigPath() throws Exception {
        solrClient.ping();

        SolrInputDocument document = new SolrInputDocument();
        document.addField("id", "devservice-1");
        document.addField("title", "Solr Dev Service");

        solrClient.add(document);
        solrClient.commit();

        assertEquals(1, solrClient.query(new SolrQuery("id:devservice-1")).getResults().getNumFound());
    }
}
