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

package org.apache.ignite.internal.tx.impl;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ignite.internal.tx.InternalTransaction;
import org.apache.ignite.internal.tx.Timestamp;
import org.apache.ignite.internal.tx.TxManager;
import org.apache.ignite.internal.tx.TxState;
import org.apache.ignite.network.NetworkAddress;
import org.apache.ignite.table.Table;
import org.apache.ignite.tx.TransactionException;
import org.jetbrains.annotations.Nullable;

/** */
public class TransactionImpl implements InternalTransaction {
    /** The timestamp. */
    private final Timestamp timestamp;

    /** TX manager. */
    private final TxManager txManager;

    /** Originator. */
    private final NetworkAddress address;

    /** */
    private Map<NetworkAddress, Set<String>> map = new ConcurrentHashMap<>();

    /** */
    private Thread t;

    /**
     * @param txManager The tx managert.
     * @param timestamp The timestamp.
     * @param address The local address.
     */
    public TransactionImpl(TxManager txManager, Timestamp timestamp, NetworkAddress address) {
        this.txManager = txManager;
        this.timestamp = timestamp;
        this.address = address;
    }

    /** {@inheritDoc} */
    @Override public Timestamp timestamp() {
        return timestamp;
    }

    /** {@inheritDoc} */
    @Override public boolean collocated() {
        return map.containsKey(address);
    }

    @Override public Map<NetworkAddress, Set<String>> map() {
        return map;
    }

    /** {@inheritDoc} */
    @Override public TxState state() {
        return txManager.state(timestamp);
    }

    /** {@inheritDoc} */
    @Override public synchronized boolean enlist(NetworkAddress node, String groupId) {
        // TODO asch remove synchronized
        return map.computeIfAbsent(node, k -> new HashSet<>()).add(groupId);
    }

    /** {@inheritDoc} */
    @Override public void commit() throws TransactionException {
        try {
            commitAsync().get();
        }
        catch (Exception e) {
            throw new TransactionException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public CompletableFuture<Void> commitAsync() {
        return finish(true);
    }

    /** {@inheritDoc} */
    @Override public void rollback() throws TransactionException {
        try {
            rollbackAsync().get();
        }
        catch (Exception e) {
            throw new TransactionException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public CompletableFuture<Void> rollbackAsync() {
        return finish(false);
    }

    /**
     * @param commit {@code True} to commit.
     * @return The future.
     */
    private CompletableFuture<Void> finish(boolean commit) {
        CompletableFuture[] futs = new CompletableFuture[map.size() + 1];

        int i = 0;

        for (Map.Entry<NetworkAddress, Set<String>> entry : map.entrySet())
            futs[i++] = txManager.finishRemote(entry.getKey(), timestamp, commit, entry.getValue());

        futs[i] = collocated() ? CompletableFuture.completedFuture(null) : commit ? txManager.commitAsync(timestamp) :
            txManager.rollbackAsync(timestamp);

        return CompletableFuture.allOf(futs);
    }

    /** {@inheritDoc} */
    @Override public Table wrap(Table t) {
        return t.withTransaction(this);
    }

    /** {@inheritDoc} */
    @Override public CompletableFuture<Table> wrapAsync(Table t) {
        // TODO asch check if already wrapped.
        return CompletableFuture.completedFuture(t.withTransaction(this));
    }

    /** {@inheritDoc} */
    @Override public void thread(Thread t) {
        this.t = t;
    }

    /** {@inheritDoc} */
    @Override public @Nullable Thread thread() {
        return t;
    }
}
