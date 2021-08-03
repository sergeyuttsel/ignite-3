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

package org.apache.ignite.internal.configuration.tree;

import java.util.Map;
import org.apache.ignite.configuration.NamedListChange;
import org.apache.ignite.configuration.annotation.Config;
import org.apache.ignite.configuration.annotation.ConfigurationRoot;
import org.apache.ignite.configuration.annotation.NamedConfigValue;
import org.apache.ignite.configuration.annotation.Value;
import org.apache.ignite.internal.configuration.TestConfigurationChanger;
import org.apache.ignite.internal.configuration.asm.ConfigurationAsmGenerator;
import org.apache.ignite.internal.configuration.storage.TestConfigurationStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Test for keys ordering in named list nodes. */
public class NamedListOrderTest {
    /** Root that has a single named list. */
    @ConfigurationRoot(rootName = "a")
    public static class AConfigurationSchema {
        /** */
        @NamedConfigValue
        public BConfigurationSchema b;
    }

    /** Named list element node that in inself contains another named list. */
    @Config
    public static class BConfigurationSchema {
        /** Every named list element node must have at least one configuration field that is not named list. */
        @Value(hasDefault = true)
        public String c = "foo";

        @NamedConfigValue
        public BConfigurationSchema b;
    }

    /** Runtime implementations generator. */
    private static ConfigurationAsmGenerator cgen;

    /** Test configuration storage. */
    private TestConfigurationStorage storage;

    /** Test configuration changer. */
    private TestConfigurationChanger changer;

    /** Instantiates {@link #cgen}. */
    @BeforeAll
    public static void beforeAll() {
        cgen = new ConfigurationAsmGenerator();
    }

    /** Nullifies {@link #cgen} to prevent memory leak from having runtime ClassLoader accessible from GC root. */
    @AfterAll
    public static void afterAll() {
        cgen = null;
    }

    /** */
    @BeforeEach
    public void before() {
        storage = new TestConfigurationStorage();

        changer = new TestConfigurationChanger(cgen);
        changer.addRootKey(AConfiguration.KEY);
        changer.register(storage);
    }

    /** */
    @AfterEach
    public void after() {
        changer.stop();
    }

    /**
     * Tests that there are no unnecessary {@code <idx>} values in the storage after all basic named list operations.
     *
     * @throws Exception If failed.
     */
    @Test
    public void storageData() throws Exception {
        // Manually instantiate configuration instance.
        var a = (AConfiguration)cgen.instantiateCfg(AConfiguration.KEY, changer);

        // Create values on several layers at the same time. They all should have <idx> = 0.
        a.b().change(b -> b.create("X", x -> x.changeB(xb -> xb.create("Z0", z0 -> {})))).get();

        assertEquals(
            Map.of(
                "a.b.X.c", "foo",
                "a.b.X.<idx>", 0,
                "a.b.X.b.Z0.c", "foo",
                "a.b.X.b.Z0.<idx>", 0
            ),
            storage.readAll().values()
        );

        BConfiguration x = a.b().get("X");

        // Append new key. It should have <idx> = 1.
        x.b().change(xb -> xb.create("Z5", z5 -> {})).get();

        assertEquals(
            Map.of(
                "a.b.X.c", "foo",
                "a.b.X.<idx>", 0,
                "a.b.X.b.Z0.c", "foo",
                "a.b.X.b.Z0.<idx>", 0,
                "a.b.X.b.Z5.c", "foo",
                "a.b.X.b.Z5.<idx>", 1
            ),
            storage.readAll().values()
        );

        // Insert new key somewhere in the middle. Index of Z5 should be updated to 2.
        x.b().change(xb -> xb.create(1, "Z2", z2 -> {})).get();

        assertEquals(
            Map.of(
                "a.b.X.c", "foo",
                "a.b.X.<idx>", 0,
                "a.b.X.b.Z0.c", "foo",
                "a.b.X.b.Z0.<idx>", 0,
                "a.b.X.b.Z2.c", "foo",
                "a.b.X.b.Z2.<idx>", 1,
                "a.b.X.b.Z5.c", "foo",
                "a.b.X.b.Z5.<idx>", 2
            ),
            storage.readAll().values()
        );

        // Insert new key somewhere in the middle. Indexes of Z3 and Z5 should be updated to 2 and 3.
        x.b().change(xb -> xb.createAfter("Z2", "Z3", z3 -> {})).get();

        assertEquals(
            Map.of(
                "a.b.X.c", "foo",
                "a.b.X.<idx>", 0,
                "a.b.X.b.Z0.c", "foo",
                "a.b.X.b.Z0.<idx>", 0,
                "a.b.X.b.Z2.c", "foo",
                "a.b.X.b.Z2.<idx>", 1,
                "a.b.X.b.Z3.c", "foo",
                "a.b.X.b.Z3.<idx>", 2,
                "a.b.X.b.Z5.c", "foo",
                "a.b.X.b.Z5.<idx>", 3
            ),
            storage.readAll().values()
        );

        // Delete key from the middle. Indexes of Z3 and Z5 should be updated to 1 and 2.
        x.b().change(xb -> xb.delete("Z2")).get();

        assertEquals(
            Map.of(
                "a.b.X.c", "foo",
                "a.b.X.<idx>", 0,
                "a.b.X.b.Z0.c", "foo",
                "a.b.X.b.Z0.<idx>", 0,
                "a.b.X.b.Z3.c", "foo",
                "a.b.X.b.Z3.<idx>", 1,
                "a.b.X.b.Z5.c", "foo",
                "a.b.X.b.Z5.<idx>", 2
            ),
            storage.readAll().values()
        );

        // Delete values on several layers simultaneously. Storage must be empty after that.
        a.b().change(b -> b.delete("X")).get();

        assertEquals(
            Map.of(),
            storage.readAll().values()
        );
    }

    /** Tests exceptions described in methods signatures. */
    @Test
    public void creationErrors() throws Exception {
        // Manually instantiate configuration instance.
        var a = (AConfiguration)cgen.instantiateCfg(AConfiguration.KEY, changer);

        a.b().change(b -> b.create("X", x -> {}).create("Y", y -> {})).get();

        // Dirty cast, but appropriate for this particular test.
        var b = (NamedListChange<BView>)a.b().value();

        // NPE in keys.
        assertThrows(NullPointerException.class, () -> b.create(null, z -> {}));
        assertThrows(NullPointerException.class, () -> b.createOrUpdate(null, z -> {}));
        assertThrows(NullPointerException.class, () -> b.create(0, null, z -> {}));
        assertThrows(NullPointerException.class, () -> b.createAfter(null, "Z", z -> {}));
        assertThrows(NullPointerException.class, () -> b.createAfter("X", null, z -> {}));

        // NPE in closures.
        assertThrows(NullPointerException.class, () -> b.create("Z", null));
        assertThrows(NullPointerException.class, () -> b.createOrUpdate("Z", null));
        assertThrows(NullPointerException.class, () -> b.create(0, "Z", null));
        assertThrows(NullPointerException.class, () -> b.createAfter("X", "Z", null));

        // Already existing keys.
        assertThrows(IllegalArgumentException.class, () -> b.create("X", x -> {}));
        assertThrows(IllegalArgumentException.class, () -> b.create(0, "X", x -> {}));
        assertThrows(IllegalArgumentException.class, () -> b.createAfter("X", "Y", y -> {}));

        // Nonexistent preceding key.
        assertThrows(IllegalArgumentException.class, () -> b.createAfter("A", "Z", z -> {}));

        // Wrong indexes.
        assertThrows(IndexOutOfBoundsException.class, () -> b.create(-1, "Z", z -> {}));
        assertThrows(IndexOutOfBoundsException.class, () -> b.create(3, "Z", z -> {}));

        // Create after delete.
        b.delete("X");
        assertThrows(IllegalArgumentException.class, () -> b.create("X", x -> {}));
        assertThrows(IllegalArgumentException.class, () -> b.create(0, "X", x -> {}));
    }
}
