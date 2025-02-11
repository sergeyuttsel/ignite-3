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

package org.apache.ignite.internal.table;

import org.apache.ignite.internal.schema.Column;
import org.apache.ignite.internal.schema.InvalidTypeException;
import org.apache.ignite.internal.schema.NativeTypes;
import org.apache.ignite.internal.schema.SchemaDescriptor;
import org.apache.ignite.internal.schema.SchemaMismatchException;
import org.apache.ignite.internal.table.impl.DummyInternalTableImpl;
import org.apache.ignite.internal.table.impl.DummySchemaManagerImpl;
import org.apache.ignite.table.RecordView;
import org.apache.ignite.table.Table;
import org.apache.ignite.table.Tuple;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Check data by strict schema.
 */
public class StrictSchemaOperationsTest {
    /**
     *
     */
    @Test
    public void columnNotExist() {
        SchemaDescriptor schema = new SchemaDescriptor(
            1,
            new Column[] {new Column("id", NativeTypes.INT64, false)},
            new Column[] {new Column("val", NativeTypes.INT64, true)}
        );

        RecordView<Tuple> recView = createTableImpl(schema).recordView();

        assertThrows(SchemaMismatchException.class, () -> recView.insert(Tuple.create().set("id", 0L).set("invalidCol", 0)));
    }

    /**
     *
     */
    @Test
    public void schemaMismatch() {
        SchemaDescriptor schema = new SchemaDescriptor(
            1,
            new Column[] {
                new Column("id", NativeTypes.INT64, false),
                new Column("affId", NativeTypes.INT64, false)
            },
            new Column[] {new Column("val", NativeTypes.INT64, true)}
        );

        Table tbl = createTableImpl(schema);

        assertThrows(SchemaMismatchException.class, () -> tbl.recordView().get(Tuple.create().set("id", 0L).set("affId", 1L).set("val", 0L)));
        assertThrows(SchemaMismatchException.class, () -> tbl.recordView().get(Tuple.create().set("id", 0L)));

        assertThrows(SchemaMismatchException.class, () -> tbl.keyValueView().get(Tuple.create().set("id", 0L)));
        assertThrows(SchemaMismatchException.class, () -> tbl.keyValueView().get(Tuple.create().set("id", 0L).set("affId", 1L).set("val", 0L)));

        assertThrows(SchemaMismatchException.class, () -> tbl.keyValueView().put(Tuple.create().set("id", 0L), Tuple.create()));
        assertThrows(SchemaMismatchException.class, () -> tbl.keyValueView().put(Tuple.create().set("id", 0L).set("affId", 1L).set("val", 0L), Tuple.create()));
        assertThrows(SchemaMismatchException.class, () -> tbl.keyValueView().put(Tuple.create().set("id", 0L).set("affId", 1L),
            Tuple.create().set("id", 0L).set("val", 0L)));
    }

    /**
     *
     */
    @Test
    public void typeMismatch() {
        SchemaDescriptor schema = new SchemaDescriptor(
            1,
            new Column[] {new Column("id", NativeTypes.INT64, false)},
            new Column[] {
                new Column("valString", NativeTypes.stringOf(3), true),
                new Column("valBytes", NativeTypes.blobOf(3), true)
            }
        );

        RecordView<Tuple> tbl = createTableImpl(schema).recordView();

        // Check not-nullable column.
        assertThrows(IllegalArgumentException.class, () -> tbl.insert(Tuple.create().set("id", null)));

        // Check length of the string column
        assertThrows(InvalidTypeException.class, () -> tbl.insert(Tuple.create().set("id", 0L).set("valString", "qweqwe")));

        // Check length of the string column
        assertThrows(InvalidTypeException.class, () -> tbl.insert(Tuple.create().set("id", 0L).set("valBytes", new byte[]{0, 1, 2, 3})));
    }

    /**
     *
     */
    @Test
    public void stringTypeMatch() {
        SchemaDescriptor schema = new SchemaDescriptor(
            1,
            new Column[] {new Column("id", NativeTypes.INT64, false)},
            new Column[] {
                new Column("valString", NativeTypes.stringOf(3), true)
            }
        );

        RecordView<Tuple> tbl = createTableImpl(schema).recordView();

        Tuple tuple = Tuple.create().set("id", 1L);

        tbl.insert(tuple.set("valString", "qwe"));
        tbl.insert(tuple.set("valString", "qw"));
        tbl.insert(tuple.set("valString", "q"));
        tbl.insert(tuple.set("valString", ""));
        tbl.insert(tuple.set("valString", null));

        // Check string 3 char length and 9 bytes.
        tbl.insert(tuple.set("valString", "我是谁"));
    }

    /**
     *
     */
    @Test
    public void bytesTypeMatch() {
        SchemaDescriptor schema = new SchemaDescriptor(
            1,
            new Column[] {new Column("id", NativeTypes.INT64, false)},
            new Column[] {
                new Column("valUnlimited", NativeTypes.BYTES, true),
                new Column("valLimited", NativeTypes.blobOf(2), true)
            });

        RecordView<Tuple> tbl = createTableImpl(schema).recordView();

        Tuple tuple = Tuple.create().set("id", 1L);

        tbl.insert(tuple.set("valUnlimited", null));
        tbl.insert(tuple.set("valLimited", null));
        tbl.insert(tuple.set("valUnlimited", new byte[2]));
        tbl.insert(tuple.set("valLimited", new byte[2]));
        tbl.insert(tuple.set("valUnlimited", new byte[3]));

        assertThrows(InvalidTypeException.class, () -> tbl.insert(tuple.set("valLimited", new byte[3])));

    }

    private TableImpl createTableImpl(SchemaDescriptor schema) {
        return new TableImpl(new DummyInternalTableImpl(), new DummySchemaManagerImpl(schema), null);
    }
}
