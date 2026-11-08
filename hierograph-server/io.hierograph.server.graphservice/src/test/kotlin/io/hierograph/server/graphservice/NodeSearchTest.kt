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
package io.hierograph.server.graphservice

import io.hierograph.server.mapping.GraphStoreNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.graphstore.model.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NodeSearchTest {

    private val graph = HGGraphFactory.createHGGraph()
    private var nextId = 1L
    private val root = HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }
    private val hierarchy = HierarchyFactory.createHierarchy(graph, root)

    private fun add(parent: HGNode, kind: Any, name: String, fqn: String): HGNode =
        HGGraphFactory.createNode(graph) { GraphStoreNodeSource(nextId++, name, fqn, fqn) }.also {
            it.kind = kind
            HierarchyFactory.addChild(hierarchy, parent, it)
        }

    private val module = add(root, JavaKinds.MODULE, "app.jar", "app.jar")
    private val pkg = add(module, JavaKinds.PACKAGE, "handler", "com.foo.handler")
    private val handler = add(pkg, JavaKinds.CLASS, "Handler", "com.foo.handler.Handler")
    private val textHandler = add(pkg, JavaKinds.CLASS, "TextHandler", "com.foo.handler.TextHandler")
    private val handlerFactory = add(pkg, JavaKinds.INTERFACE, "HandlerFactory", "com.foo.handler.HandlerFactory")
    private val handleMethod = add(handler, JavaKinds.METHOD, "void handle()", "com.foo.handler.Handler#void handle()")

    private val search = NodeSearch(HierarchicalGraphService().also { it.seed(HGModel(graph, hierarchy)) })

    @Test
    fun `ranks exact name, then name prefix, then substring, and skips members by default`() {
        assertThat(search.search("handler", null, 50))
            .containsExactly(pkg, handler, handlerFactory, textHandler)
            .doesNotContain(handleMethod)
    }

    @Test
    fun `kind filter accepts kinds and aliases, and limit takes the top matches`() {
        assertThat(search.search("handle", listOf("members"), 50)).containsExactly(handleMethod)
        assertThat(search.search("handler", listOf("java.interface", "packages"), 50)).containsExactly(pkg, handlerFactory)
        assertThat(search.search("handler", null, 2)).containsExactly(pkg, handler)
    }

    @Test
    fun `invalid kinds are reported, kinds and aliases are not`() {
        assertThat(search.invalidKinds(listOf("java.class", "classes", "types"))).containsExactly("classes")
        assertThat(search.invalidKinds(null)).isEmpty()
        assertThat(search.search("textha", null, 10)).containsExactly(textHandler)
    }
}
