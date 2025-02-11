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
import java.util.Map;
import java.util.UUID;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgnitionManager;
import org.apache.ignite.internal.ITUtils;
import org.apache.ignite.internal.schema.configuration.SchemaConfigurationConverter;
import org.apache.ignite.internal.testframework.WorkDirectory;
import org.apache.ignite.internal.testframework.WorkDirectoryExtension;
import org.apache.ignite.internal.util.IgniteUtils;
import org.apache.ignite.schema.SchemaBuilders;
import org.apache.ignite.schema.definition.ColumnType;
import org.apache.ignite.schema.definition.TableDefinition;
import org.apache.ignite.table.KeyValueView;
import org.apache.ignite.table.RecordView;
import org.apache.ignite.table.Table;
import org.apache.ignite.table.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.apache.ignite.internal.testframework.IgniteTestUtils.testNodeName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Ignition interface tests.
 */
@ExtendWith(WorkDirectoryExtension.class)
class ITDynamicTableCreationTest {
    /** Network ports of the test nodes. */
    private static final int[] PORTS = { 3344, 3345, 3346 };

    /** Nodes bootstrap configuration. */
    private final Map<String, String> nodesBootstrapCfg = new LinkedHashMap<>();

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
            "  node.metastorageNodes: [ \"" + node0Name + "\" ],\n" +
            "  network: {\n" +
            "    port: " + PORTS[0] + ",\n" +
            "    nodeFinder:{\n" +
            "      netClusterNodes: [ \"localhost:3344\", \"localhost:3345\", \"localhost:3346\" ] \n" +
            "    }\n" +
            "  }\n" +
            "}"
        );

        nodesBootstrapCfg.put(
            node1Name,
            "{\n" +
            "  node.metastorageNodes: [ \"" + node0Name + "\" ],\n" +
            "  network: {\n" +
            "    port: " + PORTS[1] + ",\n" +
            "    nodeFinder:{\n" +
            "      netClusterNodes: [ \"localhost:3344\", \"localhost:3345\", \"localhost:3346\" ]\n" +
            "    }\n" +
            "  }\n" +
            "}"
        );

        nodesBootstrapCfg.put(
            node2Name,
            "{\n" +
            "  node.metastorageNodes: [ \"" + node0Name + "\" ],\n" +
            "  network: {\n" +
            "    port: " + PORTS[2] + ",\n" +
            "    nodeFinder:{\n" +
            "      netClusterNodes: [ \"localhost:3344\", \"localhost:3345\", \"localhost:3346\" ]\n" +
            "    }\n" +
            "  }\n" +
            "}"
        );
    }

    /** */
    @AfterEach
    void tearDown() throws Exception {
        IgniteUtils.closeAll(ITUtils.reverse(clusterNodes));
    }

    /**
     * Check dynamic table creation.
     */
    @Test
    void testDynamicSimpleTableCreation() {
        nodesBootstrapCfg.forEach((nodeName, configStr) ->
            clusterNodes.add(IgnitionManager.start(nodeName, configStr, workDir.resolve(nodeName)))
        );

        assertEquals(3, clusterNodes.size());

        // Create table on node 0.
        TableDefinition schTbl1 = SchemaBuilders.tableBuilder("PUBLIC", "tbl1").columns(
            SchemaBuilders.column("key", ColumnType.INT64).asNonNull().build(),
            SchemaBuilders.column("val", ColumnType.INT32).asNullable().build()
        ).withPrimaryKey("key").build();

        clusterNodes.get(0).tables().createTable(schTbl1.canonicalName(), tblCh ->
            SchemaConfigurationConverter.convert(schTbl1, tblCh)
                .changeReplicas(1)
                .changePartitions(10)
        );

        // Put data on node 1.
        Table tbl1 = clusterNodes.get(1).tables().table(schTbl1.canonicalName());
        RecordView<Tuple> recView1 = tbl1.recordView();
        KeyValueView<Tuple, Tuple> kvView1 = tbl1.keyValueView();

        recView1.insert(Tuple.create().set("key", 1L).set("val", 111));
        kvView1.put(Tuple.create().set("key", 2L), Tuple.create().set("val", 222));

        // Get data on node 2.
        Table tbl2 = clusterNodes.get(2).tables().table(schTbl1.canonicalName());
        RecordView<Tuple> recView2 = tbl2.recordView();
        KeyValueView<Tuple, Tuple> kvView2 = tbl2.keyValueView();

        final Tuple keyTuple1 = Tuple.create().set("key", 1L);
        final Tuple keyTuple2 = Tuple.create().set("key", 2L);

        assertThrows(IllegalArgumentException.class, () -> kvView2.get(keyTuple1).value("key"));
        assertThrows(IllegalArgumentException.class, () -> kvView2.get(keyTuple2).value("key"));
        assertEquals(1, (Long)recView2.get(keyTuple1).value("key"));
        assertEquals(2, (Long)recView2.get(keyTuple2).value("key"));

        assertEquals(111, (Integer)recView2.get(keyTuple1).value("val"));
        assertEquals(111, (Integer)kvView2.get(keyTuple1).value("val"));
        assertEquals(222, (Integer)recView2.get(keyTuple2).value("val"));
        assertEquals(222, (Integer)kvView2.get(keyTuple2).value("val"));

        assertThrows(IllegalArgumentException.class, () -> kvView1.get(keyTuple1).value("key"));
        assertThrows(IllegalArgumentException.class, () -> kvView1.get(keyTuple2).value("key"));
    }

    /**
     * Check dynamic table creation.
     */
    @Test
    void testDynamicTableCreation() {
        nodesBootstrapCfg.forEach((nodeName, configStr) ->
            clusterNodes.add(IgnitionManager.start(nodeName, configStr, workDir.resolve(nodeName)))
        );

        assertEquals(3, clusterNodes.size());

        // Create table on node 0.
        TableDefinition scmTbl1 = SchemaBuilders.tableBuilder("PUBLIC", "tbl1").columns(
            SchemaBuilders.column("key", ColumnType.UUID).asNonNull().build(),
            SchemaBuilders.column("affKey", ColumnType.INT64).asNonNull().build(),
            SchemaBuilders.column("valStr", ColumnType.string()).asNullable().build(),
            SchemaBuilders.column("valInt", ColumnType.INT32).asNullable().build(),
            SchemaBuilders.column("valNull", ColumnType.INT16).asNullable().build()
        ).withPrimaryKey(
            SchemaBuilders.primaryKey()
                .withColumns("key", "affKey")
                .withAffinityColumns("affKey")
                .build()
        ).build();

        clusterNodes.get(0).tables().createTable(scmTbl1.canonicalName(), tblCh ->
            SchemaConfigurationConverter.convert(scmTbl1, tblCh)
                .changeReplicas(1)
                .changePartitions(10));

        final UUID uuid = UUID.randomUUID();
        final UUID uuid2 = UUID.randomUUID();

        // Put data on node 1.
        Table tbl1 = clusterNodes.get(1).tables().table(scmTbl1.canonicalName());
        RecordView<Tuple> recView1 = tbl1.recordView();
        KeyValueView<Tuple, Tuple> kvView1 = tbl1.keyValueView();

        recView1.insert(Tuple.create().set("key", uuid).set("affKey", 42L)
            .set("valStr", "String value").set("valInt", 73).set("valNull", null));

        kvView1.put(Tuple.create().set("key", uuid2).set("affKey", 4242L),
            Tuple.create().set("valStr", "String value 2").set("valInt", 7373).set("valNull", null));

        // Get data on node 2.
        Table tbl2 = clusterNodes.get(2).tables().table(scmTbl1.canonicalName());
        RecordView<Tuple> recView2 = tbl2.recordView();
        KeyValueView<Tuple, Tuple> kvView2 = tbl2.keyValueView();

        final Tuple keyTuple1 = Tuple.create().set("key", uuid).set("affKey", 42L);
        final Tuple keyTuple2 = Tuple.create().set("key", uuid2).set("affKey", 4242L);

        // KV view must NOT return key columns in value.
        assertThrows(IllegalArgumentException.class, () -> kvView2.get(keyTuple1).value("key"));
        assertThrows(IllegalArgumentException.class, () -> kvView2.get(keyTuple1).value("affKey"));
        assertThrows(IllegalArgumentException.class, () -> kvView2.get(keyTuple2).value("key"));
        assertThrows(IllegalArgumentException.class, () -> kvView2.get(keyTuple2).value("affKey"));

        // Record binary view MUST return key columns in value.
        assertEquals(uuid, recView2.get(keyTuple1).value("key"));
        assertEquals(42L, (Long)recView2.get(keyTuple1).value("affKey"));
        assertEquals(uuid2, recView2.get(keyTuple2).value("key"));
        assertEquals(4242L, (Long)recView2.get(keyTuple2).value("affKey"));

        assertEquals("String value", recView2.get(keyTuple1).value("valStr"));
        assertEquals(73, (Integer)recView2.get(keyTuple1).value("valInt"));
        assertNull(recView2.get(keyTuple1).value("valNull"));

        assertEquals("String value 2", recView2.get(keyTuple2).value("valStr"));
        assertEquals(7373, (Integer)recView2.get(keyTuple2).value("valInt"));
        assertNull(recView2.get(keyTuple2).value("valNull"));

        assertEquals("String value", kvView2.get(keyTuple1).value("valStr"));
        assertEquals(73, (Integer)kvView2.get(keyTuple1).value("valInt"));
        assertNull(kvView2.get(keyTuple1).value("valNull"));

        assertEquals("String value 2", kvView2.get(keyTuple2).value("valStr"));
        assertEquals(7373, (Integer)kvView2.get(keyTuple2).value("valInt"));
        assertNull(kvView2.get(keyTuple2).value("valNull"));
    }
}
