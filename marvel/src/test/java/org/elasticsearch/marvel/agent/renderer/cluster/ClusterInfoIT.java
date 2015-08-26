/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.renderer.cluster;

import org.elasticsearch.Version;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.license.core.License;
import org.elasticsearch.license.plugin.LicensePlugin;
import org.elasticsearch.marvel.MarvelPlugin;
import org.elasticsearch.marvel.agent.collector.cluster.ClusterInfoCollector;
import org.elasticsearch.marvel.agent.settings.MarvelSettings;
import org.elasticsearch.node.Node;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.*;

@ClusterScope(scope = ESIntegTestCase.Scope.SUITE, transportClientRatio = 0.0)
public class ClusterInfoIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(Node.HTTP_ENABLED, true)
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
                .put(MarvelSettings.STARTUP_DELAY, "1s")
                .put(MarvelSettings.COLLECTORS, ClusterInfoCollector.NAME)
                .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(LicensePlugin.class, MarvelPlugin.class);
    }

    @Override
    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return nodePlugins();
    }

    @Test
    public void testLicenses() throws Exception {
        final String clusterUUID = client().admin().cluster().prepareState().setMetaData(true).get().getState().metaData().clusterUUID();
        assertTrue(Strings.hasText(clusterUUID));

        logger.debug("--> waiting for licenses collector to collect data (ie, the trial marvel license)");
        GetResponse response = assertBusy(new Callable<GetResponse>() {
            @Override
            public GetResponse call() throws Exception {
                // Checks if the marvel data index exists (it should have been created by the LicenseCollector)
                assertTrue(MarvelSettings.MARVEL_DATA_INDEX_NAME + " index does not exist", client().admin().indices().prepareExists(MarvelSettings.MARVEL_DATA_INDEX_NAME).get().isExists());
                ensureYellow(MarvelSettings.MARVEL_DATA_INDEX_NAME);

                GetResponse response = client().prepareGet(MarvelSettings.MARVEL_DATA_INDEX_NAME, ClusterInfoCollector.TYPE, clusterUUID).get();
                assertTrue(MarvelSettings.MARVEL_DATA_INDEX_NAME + " document does not exist", response.isExists());
                return response;
            }
        });

        logger.debug("--> checking that the document contains license information");
        assertThat(response.getIndex(), equalTo(MarvelSettings.MARVEL_DATA_INDEX_NAME));
        assertThat(response.getType(), equalTo(ClusterInfoCollector.TYPE));
        assertThat(response.getId(), equalTo(clusterUUID));

        Map<String, Object> source = response.getSource();
        assertThat((String) source.get(ClusterInfoRenderer.Fields.CLUSTER_NAME.underscore().toString()), equalTo(cluster().getClusterName()));
        assertThat((String) source.get(ClusterInfoRenderer.Fields.VERSION.underscore().toString()), equalTo(Version.CURRENT.toString()));

        Object licensesList = source.get(ClusterInfoRenderer.Fields.LICENSES.underscore().toString());
        assertThat(licensesList, instanceOf(List.class));

        List licenses = (List) licensesList;
        assertThat(licenses.size(), equalTo(1));

        Map license = (Map) licenses.iterator().next();
        assertThat(license, instanceOf(Map.class));

        String uid = (String) ((Map) license).get(ClusterInfoRenderer.Fields.UID.underscore().toString());
        assertThat(uid, not(isEmptyOrNullString()));

        String type = (String) ((Map) license).get(ClusterInfoRenderer.Fields.TYPE.underscore().toString());
        assertThat(type, not(isEmptyOrNullString()));

        String status = (String) ((Map) license).get(ClusterInfoRenderer.Fields.STATUS.underscore().toString());
        assertThat(status, not(isEmptyOrNullString()));

        Long expiryDate = (Long) ((Map) license).get(ClusterInfoRenderer.Fields.EXPIRY_DATE_IN_MILLIS.underscore().toString());
        assertThat(expiryDate, greaterThan(0L));

        // We basically recompute the hash here
        String hkey = (String) ((Map) license).get(ClusterInfoRenderer.Fields.HKEY.underscore().toString());
        String recalculated = ClusterInfoRenderer.hash(status, uid, type, String.valueOf(expiryDate), clusterUUID);
        assertThat(hkey, equalTo(recalculated));

        assertThat((String) ((Map) license).get(ClusterInfoRenderer.Fields.FEATURE.underscore().toString()), not(isEmptyOrNullString()));
        assertThat((String) ((Map) license).get(ClusterInfoRenderer.Fields.ISSUER.underscore().toString()), not(isEmptyOrNullString()));
        assertThat((String) ((Map) license).get(ClusterInfoRenderer.Fields.ISSUED_TO.underscore().toString()), not(isEmptyOrNullString()));
        assertThat((Long) ((Map) license).get(ClusterInfoRenderer.Fields.ISSUE_DATE_IN_MILLIS.underscore().toString()), greaterThan(0L));
        assertThat((Integer) ((Map) license).get(ClusterInfoRenderer.Fields.MAX_NODES.underscore().toString()), greaterThan(0));

        Object clusterStats = source.get(ClusterStatsRenderer.Fields.CLUSTER_STATS.underscore().toString());
        assertNotNull(clusterStats);
        assertThat(clusterStats, instanceOf(Map.class));
        assertThat(((Map) clusterStats).size(), greaterThan(0));

        logger.debug("--> check that the cluster_info is not indexed");
        refresh();
        assertHitCount(client().prepareCount()
                .setIndices(MarvelSettings.MARVEL_DATA_INDEX_NAME)
                .setTypes(ClusterInfoCollector.TYPE)
                .setQuery(QueryBuilders.boolQuery()
                                .should(QueryBuilders.matchQuery(ClusterInfoRenderer.Fields.STATUS.underscore().toString(), License.Status.ACTIVE.label()))
                                .should(QueryBuilders.matchQuery(ClusterInfoRenderer.Fields.STATUS.underscore().toString(), License.Status.INVALID.label()))
                                .should(QueryBuilders.matchQuery(ClusterInfoRenderer.Fields.STATUS.underscore().toString(), License.Status.EXPIRED.label()))
                                .minimumNumberShouldMatch(1)
                ).get(), 0L);

    }
}
