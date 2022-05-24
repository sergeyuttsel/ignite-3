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

package org.apache.ignite.internal.table.distributed.command;

import java.util.Collection;
import java.util.UUID;
import org.apache.ignite.internal.schema.BinaryRow;
import org.apache.ignite.internal.tx.Timestamp;
import org.apache.ignite.raft.client.ReadCommand;
import org.jetbrains.annotations.NotNull;

/**
 * This is a command for the batch get operation.
 */
public class GetAllCommand extends MultiKeyCommand implements ReadCommand {
    /**
     * Creates a new instance of GetAllCommand with the given keys to be got. The {@code keyRows} should not be {@code null} or empty.
     *
     * @param keyRows   Binary key rows.
     * @param id The timestamp.
     *
     * @see TransactionalCommand
     */
    public GetAllCommand(@NotNull Collection<BinaryRow> keyRows, @NotNull UUID id) {
        super(keyRows, id);
    }
}
