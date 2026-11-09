/*
 * Copyright 2026 Gerd Wuetherich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hierograph.server.graphql.controller

import graphql.ErrorClassification
import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.stereotype.Component

/** No hierarchical graph is loaded, so there is nothing to query; reported as a `GRAPH_UNAVAILABLE` error. */
class GraphUnavailableException : RuntimeException(
    "No hierarchical graph is available: the server could not build one from its source. Make the source " +
        "available (from a build manifest: build the project so its classes are compiled; from a Parquet " +
        "snapshot: provide the snapshot directory), then reload the graph with the MCP reload_graph tool."
)

/** Maps [GraphUnavailableException] to a `GRAPH_UNAVAILABLE` error instead of the opaque `INTERNAL_ERROR`. */
@Component
class GraphUnavailableExceptionResolver : DataFetcherExceptionResolverAdapter() {

    public override fun resolveToSingleError(ex: Throwable, env: DataFetchingEnvironment): GraphQLError? =
        if (ex is GraphUnavailableException)
            GraphqlErrorBuilder.newError(env)
                .errorType(GRAPH_UNAVAILABLE)
                .message(ex.message)
                .build()
        else null

    companion object {
        /** The classification clients branch on, rather than parsing the message. */
        val GRAPH_UNAVAILABLE: ErrorClassification = ErrorClassification.errorClassification("GRAPH_UNAVAILABLE")
    }
}
