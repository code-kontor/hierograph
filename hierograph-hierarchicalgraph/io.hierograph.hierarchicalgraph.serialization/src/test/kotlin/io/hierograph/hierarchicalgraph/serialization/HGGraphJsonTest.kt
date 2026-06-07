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
package io.hierograph.hierarchicalgraph.serialization

import io.hierograph.hierarchicalgraph.core.model.CoreDependency
import io.hierograph.hierarchicalgraph.core.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HGModel
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class HGGraphJsonTest {

    private enum class TestKind { CLASS, INTERFACE, METHOD }

    // ── round-trip ──────────────────────────────────────────────────────

    @Test
    fun `tree structure round-trips`() {
        val g = buildGraph()
        val restored = HGGraphJson.read(HGGraphJson.write(g.model))

        assertThat(restored.hierarchy.rootNode.identifier).isEqualTo(g.model.hierarchy.rootNode.identifier)
        assertThat(restored.hierarchy.childrenOf(restored.hierarchy.rootNode))
            .hasSize(g.model.hierarchy.childrenOf(g.model.hierarchy.rootNode).size)
        assertThat(allDescendants(restored).map { it.identifier })
            .containsExactlyInAnyOrderElementsOf(allDescendants(g.model).map { it.identifier })
    }

    @Test
    fun `parent-child relationships are preserved`() {
        val g = buildGraph()
        val restored = HGGraphJson.read(HGGraphJson.write(g.model))

        for (orig in allDescendants(g.model)) {
            val copy = lookup(restored, orig.identifier)
            assertThat(restored.hierarchy.parentOf(copy)?.identifier)
                .isEqualTo(g.model.hierarchy.parentOf(orig)?.identifier)
            assertThat(restored.hierarchy.childrenOf(copy).map { it.identifier })
                .containsExactlyElementsOf(g.model.hierarchy.childrenOf(orig).map { it.identifier })
        }
    }

    @Test
    fun `core dependencies and their attributes round-trip`() {
        val g = buildGraph()
        val restored = HGGraphJson.read(HGGraphJson.write(g.model))

        val depA = g.aClass.outgoingCoreDependencies.single()
        val depAcopy = lookup(restored, g.aClass.identifier).outgoingCoreDependencies.single()
        assertThat(depAcopy.from.identifier).isEqualTo(depA.from.identifier)
        assertThat(depAcopy.to.identifier).isEqualTo(depA.to.identifier)
        assertThat(depAcopy.type).isEqualTo(depA.type)
        assertThat(depAcopy.weight).isEqualTo(depA.weight)
        assertThat(depAcopy.attributesBitmap).isEqualTo(depA.attributesBitmap)
    }

    @Test
    fun `node and dependency source identifiers preserve their Long type`() {
        val g = buildGraph()
        val restored = HGGraphJson.read(HGGraphJson.write(g.model))
        val copy = lookup(restored, g.aClass.identifier)
        assertThat(copy.identifier).isInstanceOf(java.lang.Long::class.java)
        assertThat(copy.outgoingCoreDependencies.single().dependencySource.identifier)
            .isInstanceOf(java.lang.Long::class.java)
    }

    @Test
    fun `default-source properties round-trip`() {
        val g = buildGraph()
        val restored = HGGraphJson.read(HGGraphJson.write(g.model))
        val copySource = lookup(restored, g.aClass.identifier).nodeSource as DefaultNodeSource
        assertThat(copySource.properties).containsEntry("origin", "test").containsEntry("fqn", "com.acme.A")
    }

    @Test
    fun `enum kind round-trips`() {
        val g = buildGraph()
        val restored = HGGraphJson.read(HGGraphJson.write(g.model))
        assertThat(lookup(restored, g.aClass.identifier).kind).isEqualTo(TestKind.CLASS)
        assertThat(lookup(restored, g.bIface.identifier).kind).isEqualTo(TestKind.INTERFACE)
    }

    @Test
    fun `null kind round-trips`() {
        val g = buildGraph()
        val restored = HGGraphJson.read(HGGraphJson.write(g.model))
        assertThat(restored.hierarchy.rootNode.kind).isNull()
    }

    @Test
    fun `string kind round-trips`() {
        val g = Graph()
        val node = g.add(g.root, kind = "custom-string-kind")
        val restored = HGGraphJson.read(HGGraphJson.write(g.model))
        assertThat(lookup(restored, node.identifier).kind).isEqualTo("custom-string-kind")
    }

    @Test
    fun `prettyPrint produces multi-line output that still round-trips`() {
        val g = buildGraph()
        val pretty = HGGraphJson.write(g.model, prettyPrint = true)
        assertThat(pretty).contains("\n")
        val restored = HGGraphJson.read(pretty)
        assertThat(allDescendants(restored)).hasSize(allDescendants(g.model).size)
    }

    @Test
    fun `streaming write and read round-trip`() {
        val g = buildGraph()
        val buffer = java.io.ByteArrayOutputStream()
        HGGraphJson.write(g.model, buffer)
        val restored = HGGraphJson.read(buffer.toByteArray().inputStream())
        assertThat(allDescendants(restored).map { it.identifier })
            .containsExactlyInAnyOrderElementsOf(allDescendants(g.model).map { it.identifier })
    }

    // ── graphdb sources ────────────────────────────────────────────────

    @Test
    fun `GraphDb-backed graph round-trips into Default-backed graph`() {
        // Build a small graph whose root, nodes, and dependency use the
        // graphdb-flavored sources. We never touch their lazy `labels` /
        // `properties` so Neo4j is never contacted.
        var nextId = 1L
        val coreGraph = HGGraphFactory.createHGGraph()
        val root = HGGraphFactory.createNode(coreGraph) {
            io.hierograph.hierarchicalgraph.graphdb.model.GraphDbRootNodeSource(identifier = nextId++)
        }
        val hierarchy = HierarchyFactory.createHierarchy(coreGraph, root)
        val a = HGGraphFactory.createNode(coreGraph) {
            io.hierograph.hierarchicalgraph.graphdb.model.GraphDbNodeSource(identifier = nextId++)
        }
        HierarchyFactory.addChild(hierarchy, root, a)
        val b = HGGraphFactory.createNode(coreGraph) {
            io.hierograph.hierarchicalgraph.graphdb.model.GraphDbNodeSource(identifier = nextId++)
        }
        HierarchyFactory.addChild(hierarchy, root, b)
        val dep = HGGraphFactory.createCoreDependency(a, b, "DEPENDS_ON") {
            io.hierograph.hierarchicalgraph.graphdb.model.GraphDbDependencySource(
                identifier = nextId++, type = "DEPENDS_ON"
            )
        }
        dep.weight = 7
        dep.attributesBitmap = 0b0101

        val model = HGModel(coreGraph, hierarchy)
        val restored = HGGraphJson.read(HGGraphJson.write(model))

        // Identifiers and tree shape are preserved.
        assertThat(restored.hierarchy.rootNode.identifier).isEqualTo(root.identifier)
        assertThat(restored.hierarchy.childrenOf(restored.hierarchy.rootNode).map { it.identifier })
            .containsExactlyElementsOf(hierarchy.childrenOf(root).map { it.identifier })

        // Sources read back as the plain Default variants.
        assertThat(restored.hierarchy.rootNode.nodeSource).isInstanceOf(DefaultNodeSource::class.java)
        val aCopy = lookup(restored, a.identifier)
        val depCopy = aCopy.outgoingCoreDependencies.single()
        assertThat(aCopy.nodeSource).isInstanceOf(DefaultNodeSource::class.java)
        assertThat(depCopy.dependencySource).isInstanceOf(DefaultDependencySource::class.java)

        // Dependency type, weight and attributes survive.
        assertThat(depCopy.type).isEqualTo("DEPENDS_ON")
        assertThat(depCopy.weight).isEqualTo(7)
        assertThat(depCopy.attributesBitmap).isEqualTo(0b0101)
    }

    // ── error paths ────────────────────────────────────────────────────

    @Test
    fun `unknown schema version is rejected`() {
        val g = buildGraph()
        val json = HGGraphJson.write(g.model)
            .replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":99")

        assertThatThrownBy { HGGraphJson.read(json) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("schemaVersion")
    }

    // ── builder ─────────────────────────────────────────────────────────

    private class Built(
        val model: HGModel,
        val aClass: HGNode,
        val bIface: HGNode
    )

    /**
     * root
     *  └── pkg
     *       ├── A (CLASS) — DEPENDS_ON B, weight 3, bitmap 0b0011
     *       └── B (INTERFACE)
     */
    private fun buildGraph(): Built {
        val g = Graph()
        val pkg = g.add(g.root, kind = null)
        val a = g.add(pkg, kind = TestKind.CLASS, props = mapOf("origin" to "test", "fqn" to "com.acme.A"))
        val b = g.add(pkg, kind = TestKind.INTERFACE)
        g.dep(a, b, type = "DEPENDS_ON", weight = 3, bitmap = 0b0011)
        return Built(g.model, a, b)
    }

    private class Graph {
        val root: HGNode
        val model: HGModel
        private val coreGraph = HGGraphFactory.createHGGraph()
        private val hierarchy: io.hierograph.hierarchicalgraph.core.model.Hierarchy
        private var nextId = 1L
        private val depSource = { DefaultDependencySource(identifier = nextId++) }

        init {
            root = HGGraphFactory.createNode(coreGraph) { DefaultNodeSource(identifier = nextId++) }
            hierarchy = HierarchyFactory.createHierarchy(coreGraph, root)
            model = HGModel(coreGraph, hierarchy)
        }

        fun add(parent: HGNode, kind: Any?, props: Map<String, String> = emptyMap()): HGNode {
            val node = HGGraphFactory.createNode(coreGraph) {
                DefaultNodeSource(identifier = nextId++, properties = props.toMutableMap())
            }
            node.kind = kind
            HierarchyFactory.addChild(hierarchy, parent, node)
            return node
        }

        fun dep(from: HGNode, to: HGNode, type: String, weight: Int, bitmap: Int): CoreDependency {
            val d = HGGraphFactory.createCoreDependency(from, to, type, depSource)
            d.weight = weight
            d.attributesBitmap = bitmap
            return d
        }
    }

    private fun allDescendants(model: HGModel): List<HGNode> =
        allDescendants(model.hierarchy, model.hierarchy.rootNode)

    private fun allDescendants(hierarchy: io.hierograph.hierarchicalgraph.core.model.Hierarchy, node: HGNode): List<HGNode> = buildList {
        add(node)
        for (c in hierarchy.childrenOf(node)) addAll(allDescendants(hierarchy, c))
    }

    private fun lookup(model: HGModel, id: Any): HGNode =
        model.lookupNode(id) ?: error("No node with id $id in restored graph")
}
