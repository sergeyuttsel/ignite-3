/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.runner.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgnitionManager;
import org.apache.ignite.internal.testframework.WorkDirectory;
import org.apache.ignite.internal.testframework.WorkDirectoryExtension;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.table.KeyValueView;
import org.apache.ignite.table.RecordView;
import org.apache.ignite.table.Table;
import org.apache.ignite.table.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.apache.ignite.internal.testframework.IgniteTestUtils.testNodeName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Ignition interface tests.
 */
@Disabled("https://issues.apache.org/jira/browse/IGNITE-14578")
@ExtendWith(WorkDirectoryExtension.class)
class ITTableCreationTest {
    /** Network ports of the test nodes. */
    private static final int[] PORTS = { 3344, 3345, 3346 };

    /** Nodes bootstrap configuration with preconfigured tables. */
    private final LinkedHashMap<String, String> nodesBootstrapCfg = new LinkedHashMap<>();

    /** */
    private final List<Ignite> clusterNodes = new ArrayList<>();

    /** */
    @WorkDirectory
    private Path workDir;

    /** */
    @BeforeEach
    void setUp(TestInfo testInfo) {
        String node0Name = testNodeName(testInfo, PORTS[0]);
        String node1Name = testNodeName(testInfo, PORTS[1]);
        String node2Name = testNodeName(testInfo, PORTS[2]);

        nodesBootstrapCfg.put(
            node0Name,
            "{\n" +
            "  node.metastorageNodes: [ \"" + node0Name + "\", \"" + node1Name + "\" ],\n" +
            "  network: {\n" +
            "    port: " + PORTS[0] + ",\n" +
            "    nodeFinder: {\n" +
            "      netClusterNodes: [ \"localhost:3344\", \"localhost:3345\", \"localhost:3346\" ]\n" +
            "    }\n" +
            "  },\n" +
            "  \"table\": {\n" +
            "       \"tables\": {\n" +
            "           \"tbl1\": {\n" +
            "               \"partitions\":10,\n" +
            "               \"replicas\":2,\n" +
            "               \"columns\": { \n" +
            "                   \"key\": {\n" +
            "                       \"type\": {" +
            "                           \"type\":UUID\n" +
            "                       },\n" +
            "                       \"nullable\":false\n" +
            "                   },\n" +
            "                   \"affKey\": {\n" +
            "                       \"type\": {" +
            "                           \"type\":INT64\n" +
            "                       },\n" +
            "                       \"nullable\":false\n" +
            "                   },\n" +
            "                   \"valString\": {\n" +
            "                       \"type\": {" +
            "                           \"type\":String\n" +
            "                       },\n" +
            "                       \"nullable\":false\n" +
            "                   },\n" +
            "                   \"valInt\": {\n" +
            "                       \"type\": {" +
            "                           \"type\":INT32\n" +
            "                       },\n" +
            "                       \"nullable\":false\n" +
            "                   },\n" +
            "                   \"valNullable\": {\n" +
            "                       \"type\": {" +
            "                           \"type\":String\n" +
            "                       },\n" +
            "                       \"nullable\":true\n" +
            "                   }\n" +
            "               },\n" + /* Columns. */
            "               \"indices\": {\n" +
            "                   \"PK\": {\n" +
            "                       \"type\":PRIMARY,\n" +
            "                       \"columns\": {\n" +
            "                           \"key\": {\n" +
            "                               \"asc\":true\n" +
            "                           },\n" +
            "                           \"affKey\": {}\n" +
            "                       },\n" + /* Columns. */
            "                       \"affinityColumns\":[ \"affKey\" ]\n" +
            "                   }\n" +
            "               }\n" + /* Indices. */
            "           },\n" + /* Table. */
            "\n" +
            "           \"tbl2\": {\n" + // Table minimal configuration.
            "               \"columns\": { \n" +
            "                   \"key\": {\n" +
            "                       \"type\": {" +
            "                           \"type\":INT64\n" +
            "                       },\n" +
            "                   },\n" +
            "                   \"val\": {\n" +
            "                       \"type\": {" +
            "                           \"type\":INT64\n" +
            "                       },\n" +
            "                   }\n" +
            "               },\n" + /* Columns. */
            "               \"indices\": {\n" +
            "                   \"PK\": {\n" +
            "                       \"type\":PRIMARY,\n" +
            "                       \"columns\": {\n" +
            "                           \"key\": {}\n" +
            "                       },\n" + /* Columns. */
            "                   }\n" +
            "               }\n" + /* Indices. */
            "           }\n" + /* Table. */
            "       }\n" + /* Tables. */
            "  }\n" + /* Root. */
            "}"
        );

        nodesBootstrapCfg.put(
            node1Name,
            "{\n" +
            "  node.metastorageNodes: [ \"" + node0Name + "\", \"" + node1Name + "\" ],\n" +
            "  network: {\n" +
            "    port: " + PORTS[1] + ",\n" +
            "    nodeFinder: {\n" +
            "      netClusterNodes: [ \"localhost:3344\", \"localhost:3345\", \"localhost:3346\" ]\n" +
            "    }\n" +
            "  }\n" +
            "}"
        );

        nodesBootstrapCfg.put(
            node2Name,
            "{\n" +
            "  node.metastorageNodes: [ \"" + node0Name + "\", \"" + node1Name + "\" ],\n" +
            "  network: {\n" +
            "    port: " + PORTS[2] + ",\n" +
            "    nodeFinder: {\n" +
            "      netClusterNodes: [ \"localhost:3344\", \"localhost:3345\", \"localhost:3346\" ]\n" +
            "    }\n" +
            "  }\n" +
            "}"
        );
    }

    /** */
    @AfterEach
    void tearDown() throws Exception {
        IgniteUtils.closeAll(clusterNodes);
    }

    /**
     * Check table creation via bootstrap configuration with pre-configured table.
     */
    @Test
    void testInitialSimpleTableConfiguration() {
        nodesBootstrapCfg.forEach((nodeName, configStr) ->
            clusterNodes.add(IgnitionManager.start(nodeName, configStr, workDir.resolve(nodeName)))
        );

        assertEquals(3, clusterNodes.size());

        clusterNodes.forEach(Assertions::assertNotNull);

        { /* Table 1. */
            Table tbl1 = clusterNodes.get(1).tables().table("tbl1");
            RecordView<Tuple> recView = tbl1.recordView();
            KeyValueView<Tuple, Tuple> kvView1 = tbl1.keyValueView();

            recView.insert(Tuple.create().set("key", 1L).set("val", 111));
            kvView1.put(Tuple.create().set("key", 2L), Tuple.create().set("val", 222));

            Table tbl2 = clusterNodes.get(2).tables().table("tbl1");
            RecordView<Tuple> recView2 = tbl2.recordView();
            KeyValueView<Tuple, Tuple> kvView2 = tbl2.keyValueView();

            final Tuple keyTuple1 = Tuple.create().set("key", 1L);
            final Tuple keyTuple2 = Tuple.create().set("key", 2L);

            assertEquals(111, (Integer)recView2.get(keyTuple1).value("val"));
            assertEquals(111, (Integer)kvView2.get(keyTuple1).value("val"));
            assertEquals(222, (Integer)recView2.get(keyTuple2).value("val"));
            assertEquals(222, (Integer)kvView2.get(keyTuple2).value("val"));
        }

        { /* Table 2. */
            final UUID uuid = UUID.randomUUID();
            final UUID uuid2 = UUID.randomUUID();

            // Put data on node 1.
            Table tbl1 = clusterNodes.get(1).tables().table("tbl1");
            RecordView<Tuple> recView = tbl1.recordView();
            KeyValueView<Tuple, Tuple> kvView1 = tbl1.keyValueView();

            recView.insert(Tuple.create().set("key", uuid).set("affKey", 42L)
                .set("valStr", "String value").set("valInt", 73).set("valNullable", null));

            kvView1.put(Tuple.create().set("key", uuid2).set("affKey", 4242L),
                Tuple.create().set("valStr", "String value 2").set("valInt", 7373).set("valNullable", null));

            // Get data on node 2.
            Table tbl2 = clusterNodes.get(2).tables().table("tbl1");
            RecordView<Tuple> recView2 = tbl2.recordView();
            KeyValueView<Tuple, Tuple> kvView2 = tbl2.keyValueView();

            final Tuple keyTuple1 = Tuple.create().set("key", uuid).set("affKey", 42L);
            final Tuple keyTuple2 = Tuple.create().set("key", uuid2).set("affKey", 4242L);

            assertEquals("String value", recView2.get(keyTuple1).value("valStr"));
            assertEquals("String value", kvView2.get(keyTuple1).value("valStr"));
            assertEquals("String value 2", recView2.get(keyTuple2).value("valStr"));
            assertEquals("String value 2", kvView2.get(keyTuple2).value("valStr"));
            assertEquals(73, (Integer)recView2.get(keyTuple1).value("valInt"));
            assertEquals(73, (Integer)kvView2.get(keyTuple1).value("valInt"));
            assertEquals(7373, (Integer)recView2.get(keyTuple2).value("valInt"));
            assertEquals(7373, (Integer)kvView2.get(keyTuple2).value("valInt"));
            assertNull(recView2.get(keyTuple1).value("valNullable"));
            assertNull(kvView2.get(keyTuple1).value("valNullable"));
            assertNull(recView2.get(keyTuple2).value("valNullable"));
            assertNull(kvView2.get(keyTuple2).value("valNullable"));
        }
    }
}
