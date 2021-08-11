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

package org.apache.ignite.internal.table.distributed.raft;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.table.distributed.command.DeleteAllCommand;
import org.apache.ignite.internal.table.distributed.command.DeleteCommand;
import org.apache.ignite.internal.table.distributed.command.DeleteExactAllCommand;
import org.apache.ignite.internal.table.distributed.command.DeleteExactCommand;
import org.apache.ignite.internal.table.distributed.command.GetAllCommand;
import org.apache.ignite.internal.table.distributed.command.GetAndDeleteCommand;
import org.apache.ignite.internal.table.distributed.command.GetAndReplaceCommand;
import org.apache.ignite.internal.table.distributed.command.GetAndUpsertCommand;
import org.apache.ignite.internal.table.distributed.command.GetCommand;
import org.apache.ignite.internal.table.distributed.command.InsertAllCommand;
import org.apache.ignite.internal.table.distributed.command.InsertCommand;
import org.apache.ignite.internal.table.distributed.command.ReplaceCommand;
import org.apache.ignite.internal.table.distributed.command.ReplaceIfExistCommand;
import org.apache.ignite.internal.table.distributed.command.UpsertAllCommand;
import org.apache.ignite.internal.table.distributed.command.UpsertCommand;
import org.apache.ignite.internal.table.distributed.command.response.MultiRowsResponse;
import org.apache.ignite.internal.table.distributed.command.response.SingleRowResponse;
import org.apache.ignite.internal.table.distributed.storage.VersionedRowStore;
import org.apache.ignite.lang.IgniteInternalException;
import org.apache.ignite.raft.client.ReadCommand;
import org.apache.ignite.raft.client.WriteCommand;
import org.apache.ignite.raft.client.service.CommandClosure;
import org.apache.ignite.raft.client.service.RaftGroupListener;

/**
 * Partition command handler.
 */
public class PartitionListener implements RaftGroupListener {
    /**
     * Storage.
     * This is a temporary solution, it will apply until persistence layer would not be implemented.
     * TODO: IGNITE-14790.
     */
    private final VersionedRowStore storage;

    public PartitionListener(VersionedRowStore store) {
        this.storage = store;
    }

    /** {@inheritDoc} */
    @Override public void onRead(Iterator<CommandClosure<ReadCommand>> iterator) {
        while (iterator.hasNext()) {
            CommandClosure<ReadCommand> clo = iterator.next();

            if (clo.command() instanceof GetCommand) {
                clo.result(new SingleRowResponse(storage.get(((GetCommand)clo.command()).getKeyRow(), null).join()));
            }
            else if (clo.command() instanceof GetAllCommand) {
                Set<BinaryRow> keyRows = ((GetAllCommand)clo.command()).getKeyRows();

                assert keyRows != null && !keyRows.isEmpty();

                // TODO asch all reads are sequeti
                clo.result(new MultiRowsResponse(storage.getAll(keyRows, null).join()));
            }
            else
                assert false : "Command was not found [cmd=" + clo.command() + ']';
        }
    }

    /** {@inheritDoc} */
    @Override public void onWrite(Iterator<CommandClosure<WriteCommand>> iterator) {
        while (iterator.hasNext()) {
            CommandClosure<WriteCommand> clo = iterator.next();

            if (clo.command() instanceof InsertCommand) {
                BinaryRow row = ((InsertCommand)clo.command()).getRow();

                assert row.hasValue() : "Insert command should have a value.";

                clo.result(storage.insert(row, null).join());
            }
            else if (clo.command() instanceof DeleteCommand)
                clo.result(storage.delete(((DeleteCommand)clo.command()).getKeyRow(), null).join());
            else if (clo.command() instanceof ReplaceCommand) {
                ReplaceCommand cmd = ((ReplaceCommand)clo.command());

                BinaryRow expected = cmd.getOldRow();

                clo.result(storage.replace(expected, null).join());
            }
            else if (clo.command() instanceof UpsertCommand) {
                BinaryRow row = ((UpsertCommand)clo.command()).getRow();

                assert row.hasValue() : "Upsert command should have a value.";

                storage.upsert(row, null);

                clo.result(null);
            }
            else if (clo.command() instanceof InsertAllCommand) {
                Set<BinaryRow> rows = ((InsertAllCommand)clo.command()).getRows();

                assert rows != null && !rows.isEmpty();

                clo.result(new MultiRowsResponse(storage.insertAll(rows, null).join()));
            }
            else if (clo.command() instanceof UpsertAllCommand) {
                Set<BinaryRow> rows = ((UpsertAllCommand)clo.command()).getRows();

                assert rows != null && !rows.isEmpty();

                storage.upsertAll(rows, null).join();

                clo.result(null);
            }
            else if (clo.command() instanceof DeleteAllCommand) {
                Set<BinaryRow> rows = ((DeleteAllCommand)clo.command()).getRows();

                assert rows != null && !rows.isEmpty();

                clo.result(new MultiRowsResponse(storage.deleteAll(rows, null).join()));
            }
            else if (clo.command() instanceof DeleteExactCommand) {
                BinaryRow row = ((DeleteExactCommand)clo.command()).getRow();

                assert row != null;
                assert row.hasValue();

                clo.result(storage.deleteExact(row, null).join());
            }
            else if (clo.command() instanceof DeleteExactAllCommand) {
                Set<BinaryRow> rows = ((DeleteExactAllCommand)clo.command()).getRows();

                assert rows != null && !rows.isEmpty();

                clo.result(new MultiRowsResponse(storage.deleteAll(rows, null).join()));
            }
            else if (clo.command() instanceof ReplaceIfExistCommand) {
                BinaryRow row = ((ReplaceIfExistCommand)clo.command()).getRow();

                assert row != null;

                clo.result(storage.replace(row, null).join());
            }
            else if (clo.command() instanceof GetAndDeleteCommand) {
                BinaryRow row = ((GetAndDeleteCommand)clo.command()).getKeyRow();

                assert row != null;

                clo.result(new SingleRowResponse(storage.getAndDelete(row, null).join()));
            }
            else if (clo.command() instanceof GetAndReplaceCommand) {
                BinaryRow row = ((GetAndReplaceCommand)clo.command()).getRow();

                assert row != null && row.hasValue();

                clo.result(new SingleRowResponse(storage.getAndReplace(row, null).join()));
            }
            else if (clo.command() instanceof GetAndUpsertCommand) {
                BinaryRow row = ((GetAndUpsertCommand)clo.command()).getKeyRow();

                assert row != null && row.hasValue();

                clo.result(new SingleRowResponse(storage.getAndUpsert(row, null).join()));
            }
            else
                assert false : "Command was not found [cmd=" + clo.command() + ']';
        }
    }

    /** {@inheritDoc} */
    @Override public void onSnapshotSave(Path path, Consumer<Throwable> doneClo) {
        // Not implemented yet.
    }

    /** {@inheritDoc} */
    @Override public boolean onSnapshotLoad(Path path) {
        // Not implemented yet.
        return false;
    }

    /** {@inheritDoc} */
    @Override public void onShutdown() {
        try {
            storage.close();
        }
        catch (Exception e) {
            throw new IgniteInternalException("Failed to close storage: " + e.getMessage(), e);
        }
    }

    /**
     * Wrapper provides correct byte[] comparison.
     */
    private static class KeyWrapper {
        /** Data. */
        private final byte[] data;

        /** Hash. */
        private final int hash;

        /**
         * Constructor.
         *
         * @param data Wrapped data.
         */
        KeyWrapper(byte[] data, int hash) {
            assert data != null;

            this.data = data;
            this.hash = hash;
        }

        /** {@inheritDoc} */
        @Override public boolean equals(Object o) {
            if (this == o)
                return true;

            if (o == null || getClass() != o.getClass())
                return false;

            KeyWrapper wrapper = (KeyWrapper)o;
            return Arrays.equals(data, wrapper.data);
        }

        /** {@inheritDoc} */
        @Override public int hashCode() {
            return hash;
        }
    }

    /**
     * Compares two rows.
     *
     * @param row Row to compare.
     * @param row2 Row to compare.
     * @return True if these rows is equivalent, false otherwise.
     */
    private boolean equalValues(BinaryRow row, BinaryRow row2) {
        if (row == row2)
            return true;

        if (row == null || row2 == null)
            return false;

        if (row.hasValue() ^ row2.hasValue())
            return false;

        return row.valueSlice().compareTo(row2.valueSlice()) == 0;
    }
}
