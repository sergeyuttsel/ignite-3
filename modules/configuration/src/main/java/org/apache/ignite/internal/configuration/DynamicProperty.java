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

package org.apache.ignite.internal.configuration;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;
import java.util.concurrent.CompletableFuture;
import org.apache.ignite.configuration.ConfigurationValue;
import org.apache.ignite.configuration.RootKey;
import org.apache.ignite.internal.configuration.tree.ConfigurationSource;
import org.apache.ignite.internal.configuration.tree.ConstructableTreeNode;
import org.apache.ignite.internal.tostring.S;

/**
 * Holder for property value.
 * Expected to be used with numbers, strings and other immutable objects, e.g. IP addresses.
 */
public class DynamicProperty<T extends Serializable> extends ConfigurationNode<T> implements ConfigurationValue<T> {
    /**
     * Constructor.
     *
     * @param prefix Property prefix.
     * @param key Property name.
     * @param rootKey Root key.
     * @param changer Configuration changer.
     * @param listenOnly Only adding listeners mode, without the ability to get or update the property value.
     */
    public DynamicProperty(
        List<String> prefix,
        String key,
        RootKey<?, ?> rootKey,
        DynamicConfigurationChanger changer,
        boolean listenOnly
    ) {
        super(prefix, key, rootKey, changer, listenOnly);
    }

    /** {@inheritDoc} */
    @Override public T value() {
        return refreshValue();
    }

    /** {@inheritDoc} */
    @Override public CompletableFuture<Void> update(T newValue) {
        Objects.requireNonNull(newValue, "Configuration value cannot be null.");

        if (listenOnly)
            throw listenOnlyException();

        assert keys instanceof RandomAccess;
        assert !keys.isEmpty();

        ConfigurationSource src = new ConfigurationSource() {
            /** Current index in the {@code keys}. */
            private int level = 0;

            /** {@inheritDoc} */
            @Override public void descend(ConstructableTreeNode node) {
                assert level < keys.size();

                node.construct(keys.get(level++), this, true);
            }

            /** {@inheritDoc} */
            @Override public <T> T unwrap(Class<T> clazz) {
                assert level == keys.size();

                assert clazz.isInstance(newValue);

                return clazz.cast(newValue);
            }

            /** {@inheritDoc} */
            @Override public void reset() {
                level = 0;
            }
        };

        // Use resulting tree as update request for the storage.
        return changer.change(src);
    }

    /** {@inheritDoc} */
    @Override public String key() {
        return key;
    }

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(DynamicProperty.class, this, "key", key, "value", value());
    }
}
