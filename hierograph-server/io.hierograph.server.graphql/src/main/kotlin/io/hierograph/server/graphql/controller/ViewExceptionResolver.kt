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
import io.hierograph.server.graphservice.views.InvalidViewException
import io.hierograph.server.graphservice.views.UnknownViewException
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.stereotype.Component

/**
 * Maps a `view` argument that names no view to a `VIEW_NOT_FOUND` error, and one whose document does not
 * parse to `VIEW_INVALID`; both carry the exception's message, which lists the known views or the problems.
 */
@Component
class ViewExceptionResolver : DataFetcherExceptionResolverAdapter() {

    public override fun resolveToSingleError(ex: Throwable, env: DataFetchingEnvironment): GraphQLError? {
        val classification = when (ex) {
            is UnknownViewException -> VIEW_NOT_FOUND
            is InvalidViewException -> VIEW_INVALID
            else -> return null
        }
        return GraphqlErrorBuilder.newError(env).errorType(classification).message(ex.message).build()
    }

    companion object {
        val VIEW_NOT_FOUND: ErrorClassification = ErrorClassification.errorClassification("VIEW_NOT_FOUND")
        val VIEW_INVALID: ErrorClassification = ErrorClassification.errorClassification("VIEW_INVALID")
    }
}
