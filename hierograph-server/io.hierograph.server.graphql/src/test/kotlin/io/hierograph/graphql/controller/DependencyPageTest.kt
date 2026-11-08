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
package io.hierograph.graphql.controller

import graphql.Scalars
import graphql.execution.ExecutionStepInfo
import graphql.execution.MergedField
import graphql.execution.ResultPath
import graphql.language.Field
import graphql.schema.DataFetchingEnvironmentImpl
import io.hierograph.hierarchicalgraph.model.HGCoreDependency
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.graphql.execution.ErrorType
import java.lang.reflect.Proxy

class DependencyPageTest {

    /** createPage only slices the list, so the elements can be inert stand-ins with identity equality. */
    private val deps: List<HGCoreDependency> = List(5) {
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HGCoreDependency::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args!![0]
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "dep@${System.identityHashCode(proxy)}"
                else -> null
            }
        } as HGCoreDependency
    }

    @Test
    fun `pages are 1-based and the last page is partial`() {
        val first = DependencySetController.createPage(deps, 1, 2)
        assertThat(first.dependencies).containsExactly(deps[0], deps[1])
        assertThat(first.pageInfo.maxPages).isEqualTo(3)
        assertThat(first.pageInfo.totalCount).isEqualTo(5)

        assertThat(DependencySetController.createPage(deps, 3, 2).dependencies).containsExactly(deps[4])
        assertThat(DependencySetController.createPage(deps, 4, 2).dependencies).isEmpty()
    }

    @Test
    fun `a page number below 1 is rejected with a clear message`() {
        assertThatThrownBy { DependencySetController.createPage(deps, 0, 2) }
            .isInstanceOf(InvalidArgumentException::class.java)
            .hasMessageContaining("pageNumber must be >= 1")
        assertThatThrownBy { DependencySetController.createPage(deps, -1, 2) }
            .isInstanceOf(InvalidArgumentException::class.java)
    }

    @Test
    fun `a page size below 1 is rejected`() {
        assertThatThrownBy { DependencySetController.createPage(deps, 1, 0) }
            .isInstanceOf(InvalidArgumentException::class.java)
            .hasMessageContaining("pageSize must be >= 1")
    }

    @Test
    fun `the resolver maps InvalidArgumentException to BAD_REQUEST and ignores other exceptions`() {
        val resolver = InvalidArgumentExceptionResolver()
        val env = DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
            .mergedField(MergedField.newMergedField(Field("dependencyPage")).build())
            .executionStepInfo(
                ExecutionStepInfo.newExecutionStepInfo().type(Scalars.GraphQLInt).path(ResultPath.rootPath()).build()
            )
            .build()

        val error = resolver.resolveToSingleError(InvalidArgumentException("boom"), env)
        assertThat(error).isNotNull
        assertThat(error!!.errorType).isEqualTo(ErrorType.BAD_REQUEST)
        assertThat(error.message).isEqualTo("boom")

        assertThat(resolver.resolveToSingleError(IllegalStateException("x"), env)).isNull()
    }
}
