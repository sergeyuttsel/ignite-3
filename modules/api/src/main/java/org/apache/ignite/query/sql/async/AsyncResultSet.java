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

package org.apache.ignite.query.sql.async;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.apache.ignite.query.sql.QueryType;
import org.apache.ignite.query.sql.SqlResultSetMeta;
import org.apache.ignite.query.sql.SqlRow;

/**
 * Asynchronous result set.
 */
public interface AsyncResultSet {
    /**
     * Returns query`s unique identifier.
     *
     * @return Query id.
     */
    UUID queryId();

    /**
     * Returns metadata for the results.
     *
     * @return ResultSet metadata.
     */
    SqlResultSetMeta metadata();

    /**
     * @return Query type.
     * @see QueryType
     */
    QueryType queryType();

    /**
     * @return Current page rows.
     */
    Iterable<SqlRow> currentPage();

    /**
     * Fetch the next page of results asynchronously.
     *
     * @return Operation future.
     */
    CompletionStage<AsyncResultSet> fetchNextPage();

    /**
     * @return Whether there are more pages of results.
     */
    boolean hasMorePages();
}
