/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.tx.message;

import java.io.Serializable;
import java.util.Set;
import org.apache.ignite.internal.tx.Timestamp;
import org.apache.ignite.network.NetworkMessage;
import org.apache.ignite.network.annotations.Transferable;

/**
 * Submit an action to a replication group.
 */
@Transferable(value = TxMessageGroup.TX_FINISH_REQUEST, autoSerializable = false)
public interface TxFinishRequest extends NetworkMessage, Serializable {
    /**
     * @return The timestamp.
     */
    Timestamp timestamp();

    /**
     * @return {@code True} to commit.
     */
    boolean commit();

    /**
     * @return Enlisted partition groups.
     */
    Set<String> partitions();
}
