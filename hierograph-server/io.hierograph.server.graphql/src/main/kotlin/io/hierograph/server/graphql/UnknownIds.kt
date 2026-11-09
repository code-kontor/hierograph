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

import graphql.GraphqlErrorBuilder
import graphql.execution.DataFetcherResult
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.InstrumentationState
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters
import graphql.schema.DataFetcher
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component
import java.util.concurrent.CompletionStage

/**
 * Makes ids that name nothing visible to the client. A stale id is not a mistake — a client may hold ids
 * across a rebuild — so a resolver still answers without it, but it reports the id with [node] or
 * [dependency]. [UnknownIdReporting] collects the reports of one data fetcher and attaches them to its
 * result as one non-fatal `NOT_FOUND` error, so a client can tell a stale id from an empty result.
 *
 * Reports outside a collecting data fetcher (a resolver called directly, e.g. in a test) are discarded.
 */
object UnknownIds {

    private val collected = ThreadLocal<Report?>()

    /** The unknown ids reported while one data fetcher ran. */
    class Report {
        val nodeIds = linkedSetOf<String>()
        val dependencyIds = linkedSetOf<String>()
        val isEmpty: Boolean get() = nodeIds.isEmpty() && dependencyIds.isEmpty()
    }

    /** Reports a node id that names no node. */
    fun node(id: String) {
        collected.get()?.nodeIds?.add(id)
    }

    /** Reports a dependency id that names no dependency. */
    fun dependency(id: String) {
        collected.get()?.dependencyIds?.add(id)
    }

    /** Runs [block], collecting the ids it reports; nested calls report into the outer collection. */
    fun <T> collecting(block: () -> T): Pair<T, Report> {
        collected.get()?.let { return block() to Report() }
        val report = Report()
        collected.set(report)
        try {
            return block() to report
        } finally {
            collected.remove()
        }
    }
}

/** Attaches the ids a data fetcher reports through [UnknownIds] to its result as a `NOT_FOUND` error. */
@Component
class UnknownIdReporting : Instrumentation {

    override fun instrumentDataFetcher(
        dataFetcher: DataFetcher<*>,
        parameters: InstrumentationFieldFetchParameters,
        state: InstrumentationState?,
    ): DataFetcher<*> {
        if (parameters.isTrivialDataFetcher) return dataFetcher
        return DataFetcher { env ->
            val (value, report) = UnknownIds.collecting { dataFetcher.get(env) }
            if (report.isEmpty) return@DataFetcher value
            val parts = buildList {
                if (report.nodeIds.isNotEmpty()) add("no node with id ${report.nodeIds.joinToString(", ") { "'$it'" }}")
                if (report.dependencyIds.isNotEmpty()) add("no dependency with id ${report.dependencyIds.joinToString(", ") { "'$it'" }}")
            }
            val error = GraphqlErrorBuilder.newError(env)
                .errorType(ErrorType.NOT_FOUND)
                .message("Unknown ids, left out of the result: ${parts.joinToString("; ")}")
                .extensions(
                    mapOf("unknownNodeIds" to report.nodeIds.toList(), "unknownDependencyIds" to report.dependencyIds.toList())
                )
                .build()
            fun withError(data: Any?): DataFetcherResult<Any> = DataFetcherResult.newResult<Any>().data(data).error(error).build()
            if (value is CompletionStage<*>) value.thenApply { withError(it) } else withError(value)
        }
    }
}
