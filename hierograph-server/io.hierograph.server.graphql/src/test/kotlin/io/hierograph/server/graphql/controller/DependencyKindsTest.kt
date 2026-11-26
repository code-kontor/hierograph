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

import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.server.graphql.model.DependencyKindCount
import io.hierograph.server.mapping.GraphStoreDependencySource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DependencyKindsTest {

    private val graph = HGGraphFactory.createHGGraph()
    private val a = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = 1L) }
    private val b = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = 2L) }
    private val c = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = 3L) }

    @Test
    fun `a dependency's kinds, the most frequent first, ties by name`() {
        val dependency = HGGraphFactory.createCoreDependency(a, b, "DEPENDS_ON") {
            GraphStoreDependencySource(
                mapOf(DependencyKind.HAS_TYPE to 1, DependencyKind.CALLS to 3, DependencyKind.EXTENDS to 1),
            )
        }

        assertThat(DependencyController().kinds(dependency)).containsExactly(
            DependencyKindCount("calls", 3),
            DependencyKindCount("extends", 1),
            DependencyKindCount("has_type", 1),
        )
    }

    @Test
    fun `none for a graph that doesn't record kinds`() {
        val dependency = HGGraphFactory.createCoreDependency(a, c, "DEPENDS_ON") { DefaultDependencySource(9L) }

        assertThat(DependencyController().kinds(dependency)).isEmpty()
    }
}
