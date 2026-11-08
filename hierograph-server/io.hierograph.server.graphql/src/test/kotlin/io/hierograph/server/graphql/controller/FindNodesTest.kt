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

import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.NodeSearch
import io.hierograph.server.mapping.GraphStoreNodeSource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.graphstore.model.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class FindNodesTest {

    private val graph = HGGraphFactory.createHGGraph()
    private var nextId = 1L
    private val root = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)

    private fun add(parent: HGNode, kind: Any, name: String, fqn: String): HGNode =
        HGGraphFactory.createNode(graph) { GraphStoreNodeSource(nextId++, name, fqn, fqn) }.also {
            it.kind = kind
            HierarchyFactory.addChild(hierarchy, parent, it)
        }

    private val module = add(root, JavaKinds.MODULE, "app.jar", "app.jar") // the real top level is always modules
    private val pkg = add(module, JavaKinds.PACKAGE, "handler", "com.foo.handler")
    private val handler = add(pkg, JavaKinds.CLASS, "Handler", "com.foo.handler.Handler")
    private val textHandler = add(pkg, JavaKinds.CLASS, "TextHandler", "com.foo.handler.TextHandler")

    private val controller = HierarchicalGraphController(
        NodeSearch(HierarchicalGraphService().also { it.seed(HGModel(graph, hierarchy)) })
    )

    @Test
    fun `searches through the shared NodeSearch, honouring kinds and limit`() {
        assertThat(controller.findNodes(hierarchy, "handler", null, 50)).containsExactly(pkg, handler, textHandler)
        assertThat(controller.findNodes(hierarchy, "handler", listOf("types"), 1)).containsExactly(handler)
    }

    @Test
    fun `rejects unknown kinds, an out-of-range limit, a blank name, and a missing search index`() {
        assertThatThrownBy { controller.findNodes(hierarchy, "handler", listOf("classes"), 10) }
            .isInstanceOf(InvalidArgumentException::class.java)
            .hasMessageContaining("'classes'")
            .hasMessageContaining("java.class")
        assertThatThrownBy { controller.findNodes(hierarchy, "x", null, 0) }
            .isInstanceOf(InvalidArgumentException::class.java).hasMessageContaining("limit")
        assertThatThrownBy { controller.findNodes(hierarchy, "x", null, HierarchicalGraphController.MAX_FIND_LIMIT + 1) }
            .isInstanceOf(InvalidArgumentException::class.java)
        assertThatThrownBy { controller.findNodes(hierarchy, "  ", null, 10) }
            .isInstanceOf(InvalidArgumentException::class.java).hasMessageContaining("name")
        assertThatThrownBy { HierarchicalGraphController().findNodes(hierarchy, "x", null, 10) }
            .isInstanceOf(InvalidArgumentException::class.java).hasMessageContaining("not available")
    }
}
