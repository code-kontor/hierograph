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
package io.hierograph.hierarchicalgraph.graphdb.model

import io.hierograph.hierarchicalgraph.core.model.HierarchicalGraphFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class GraphDbSourcesTest {

    @Test
    fun `GraphDbRootNodeSource has empty properties and labels`() {
        val source = GraphDbRootNodeSource(identifier = 1L)
        assertThat(source.properties).isEmpty()
        assertThat(source.labels).isEmpty()
    }

    @Test
    fun `GraphDbRootNodeSource boltClient is initially null`() {
        val source = GraphDbRootNodeSource(identifier = 1L)
        assertThat(source.boltClient).isNull()
    }

    @Test
    fun `GraphDbNodeSource identifier is set correctly`() {
        val source = GraphDbNodeSource(identifier = 42L)
        assertThat(source.identifier).isEqualTo(42L)
    }

    @Test
    fun `GraphDbNodeSource can be used as INodeSource in factory`() {
        val root = HierarchicalGraphFactory.createRootNode { GraphDbRootNodeSource(identifier = 1L) }
        val node = HierarchicalGraphFactory.createNode(root, root) { GraphDbNodeSource(identifier = 100L) }

        assertThat(node.identifier).isEqualTo(100L)
        assertThat(node.nodeSource).isInstanceOf(GraphDbNodeSource::class.java)
    }

    @Test
    fun `GraphDbDependencySource type is set correctly`() {
        val source = GraphDbDependencySource(identifier = 10L, type = "DEPENDS_ON")
        assertThat(source.type).isEqualTo("DEPENDS_ON")
        assertThat(source.identifier).isEqualTo(10L)
    }

    @Test
    fun `GraphDbDependencySource userObject`() {
        val source = GraphDbDependencySource(identifier = 10L, type = "USES")
        assertThat(source.userObject).isNull()

        source.userObject = "hello"
        assertThat(source.getUserObject(String::class.java)).isEqualTo("hello")
        assertThat(source.getUserObject(Int::class.java)).isNull()
    }

    @Test
    fun `GraphDbDependencySource can be used as IDependencySource in factory`() {
        val root = HierarchicalGraphFactory.createRootNode { GraphDbRootNodeSource(identifier = 1L) }
        val a = HierarchicalGraphFactory.createNode(root, root) { GraphDbNodeSource(identifier = 2L) }
        val b = HierarchicalGraphFactory.createNode(root, root) { GraphDbNodeSource(identifier = 3L) }

        val dep = HierarchicalGraphFactory.createCoreDependency(
            a, b, "USES", { GraphDbDependencySource(identifier = 50L, type = "USES") }
        )

        assertThat(dep.dependencySource).isInstanceOf(GraphDbDependencySource::class.java)
        val depSource = dep.dependencySource as GraphDbDependencySource
        assertThat(depSource.type).isEqualTo("USES")
    }

    @Test
    fun `GraphDbNodeSource properties access without boltClient throws`() {
        val root = HierarchicalGraphFactory.createRootNode { GraphDbRootNodeSource(identifier = 1L) }
        val node = HierarchicalGraphFactory.createNode(root, root) { GraphDbNodeSource(identifier = 100L) }

        val nodeSource = node.nodeSource as GraphDbNodeSource
        assertThatThrownBy { nodeSource.properties }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No bolt client set")
    }

    @Test
    fun `GraphDbDependencySource properties access without boltClient throws`() {
        val root = HierarchicalGraphFactory.createRootNode { GraphDbRootNodeSource(identifier = 1L) }
        val a = HierarchicalGraphFactory.createNode(root, root) { GraphDbNodeSource(identifier = 2L) }
        val b = HierarchicalGraphFactory.createNode(root, root) { GraphDbNodeSource(identifier = 3L) }

        val dep = HierarchicalGraphFactory.createCoreDependency(
            a, b, "USES", { GraphDbDependencySource(identifier = 50L, type = "USES") }
        )

        val depSource = dep.dependencySource as GraphDbDependencySource
        assertThatThrownBy { depSource.properties }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("No bolt client set")
    }
}
