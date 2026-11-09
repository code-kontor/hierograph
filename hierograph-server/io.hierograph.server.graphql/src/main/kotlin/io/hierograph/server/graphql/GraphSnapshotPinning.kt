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
package io.hierograph.server.graphql

import graphql.GraphQLContext
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import io.hierograph.server.graphservice.GraphSnapshot
import io.hierograph.server.graphservice.HierarchicalGraphService
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.graphql.server.WebGraphQlResponse
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/**
 * Answers each GraphQL request from one graph, as the MCP tools answer each call from one graph.
 *
 * Resolvers read the [HierarchicalGraphService] many times per request. As an interceptor, this captures
 * the snapshot that is live when the request arrives into the request's [GraphQLContext]; as an
 * instrumentation, it pins that snapshot around every data fetcher, whichever thread runs it. A reload
 * that completes mid-request therefore never mixes two graphs into one response. Batch loaders run outside
 * any data fetcher and pin through [pinned] themselves.
 */
@Component
class GraphSnapshotPinning(private val graphService: HierarchicalGraphService) : WebGraphQlInterceptor, Instrumentation {

    override fun intercept(request: WebGraphQlRequest, chain: WebGraphQlInterceptor.Chain): Mono<WebGraphQlResponse> {
        val snapshot = graphService.liveSnapshot()
        if (snapshot != null) {
            request.configureExecutionInput { _, builder -> builder.graphQLContext(mapOf(SNAPSHOT_KEY to snapshot)).build() }
        }
        return chain.next(request)
    }

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?,
    ): DataFetcher<*> =
        if (parameters.isTrivialDataFetcher) dataFetcher
        else DataFetcher { env -> pinned(env.graphQlContext) { dataFetcher.get(env) } }

    /** Runs [block] with the snapshot captured for the request of [context] pinned; unpinned if none was. */
    fun <T> pinned(context: GraphQLContext, block: () -> T): T =
        graphService.withPinnedSnapshot(context.get<GraphSnapshot?>(SNAPSHOT_KEY), block)

    companion object {
        /** The [GraphQLContext] key under which [intercept] stores the request's snapshot. */
        internal val SNAPSHOT_KEY = GraphSnapshotPinning::class.qualifiedName + ".snapshot"
    }
}
