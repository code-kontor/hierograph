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

import graphql.GraphQLError
import graphql.GraphqlErrorBuilder
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.stereotype.Component

/** A client-supplied argument is out of range; reported as a `BAD_REQUEST` GraphQL error with [message]. */
class InvalidArgumentException(message: String) : RuntimeException(message)

/** Maps [InvalidArgumentException] to a `BAD_REQUEST` error instead of the opaque `INTERNAL_ERROR`. */
@Component
class InvalidArgumentExceptionResolver : DataFetcherExceptionResolverAdapter() {

    public override fun resolveToSingleError(ex: Throwable, env: DataFetchingEnvironment): GraphQLError? =
        if (ex is InvalidArgumentException)
            GraphqlErrorBuilder.newError(env)
                .errorType(ErrorType.BAD_REQUEST)
                .message(ex.message)
                .build()
        else null
}
