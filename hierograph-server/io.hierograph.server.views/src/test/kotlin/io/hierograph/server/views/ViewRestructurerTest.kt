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
package io.hierograph.server.views

import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.hierarchicalgraph.model.SyntheticNodeSource
import io.hierograph.hierarchicalgraph.model.internal.HGGraphImpl
import io.hierograph.server.views.ViewFinding.Code
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Restructuring views (views specification, §6.4) on the [StoreReactor]. */
class ViewRestructurerTest {

    private val r = StoreReactor()

    private fun materialize(json: String) = ViewMaterializer.materialize(r.model, ViewDocumentParser.parse(json))

    /** A restructuring view named `v` with [modules] (names) and [moves] (JSON objects). */
    private fun view(modules: List<String>, vararg moves: String, groups: String = "[]") = materialize(
        """{ "name": "v", "restructure": true, "modules": [${modules.joinToString { "{ \"name\": \"$it\" }" }}],
             "moves": [${moves.joinToString()}], "groups": $groups }""",
    )

    private fun move(select: String, to: String) = """{ "select": $select, "to": "$to" }"""

    private fun pkg(fqn: String, module: String = "acme:store:1.0") = """{ "match": "$fqn", "kind": "java.package", "within": "$module" }"""

    private fun codes(view: MaterializedView) = view.report.findings.map { it.code }

    @Nested
    inner class ExtractAPackage {

        private val extract = view(listOf("acme.parquet"), move(pkg("com.acme.store.parquet"), "acme.parquet"))
        private val h = extract.model.hierarchy

        @Test
        fun `the package moves into the virtual module under its own name, with virtual packages above it`() {
            val module = extract.virtualModules.getValue("acme.parquet")
            val parquet = r.node("com.acme.store.parquet")

            assertThat(h.parentOf(module)).isSameAs(h.rootNode)
            assertThat(module.kind).isEqualTo(JavaKinds.MODULE)
            assertThat(r.path(parquet, h)).containsExactly("acme.parquet", "com", "com.acme", "com.acme.store", "com.acme.store.parquet")
            assertThat(generateSequence(h.parentOf(parquet)) { h.parentOf(it) }.takeWhile { it !== module }.toList())
                .allMatch { ViewNodes.isVirtual(it) && it.kind == JavaKinds.PACKAGE }
            assertThat(r.children(parquet, h)).containsExactly(
                "com.acme.store.parquet.Reader", "com.acme.store.parquet.Reader\$Page", "com.acme.store.parquet.Writer",
            )
            assertThat(r.children(r.node("com.acme.store", "acme:store:1.0"), h)).doesNotContain("com.acme.store.parquet")
        }

        @Test
        fun `real nodes keep their handles, virtual ones derive theirs from the view`() {
            val parquet = r.node("com.acme.store.parquet")

            assertThat(h.lookupNode(parquet.identifier)).isSameAs(parquet)
            assertThat(extract.virtualModules.getValue("acme.parquet").identifier).isEqualTo(ViewNodes.moduleHandle("v", "acme.parquet"))
            assertThat(h.parentOf(parquet)!!.identifier).isEqualTo(ViewNodes.packageHandle("v", "acme.parquet", "com.acme.store"))
            assertThat(NodeMetadata.getQualifiedName(h.parentOf(parquet)!!)).isEqualTo("com.acme.store")
        }

        @Test
        fun `the effect names the new module dependencies and the cycle they form`() {
            val effect = extract.effect!!

            assertThat(effect.added).containsExactlyInAnyOrder(
                ModuleDependency("acme.parquet", "acme:store:1.0", 5),
                ModuleDependency("acme:store:1.0", "acme.parquet", 3),
            )
            assertThat(effect.removed).isEmpty()
            assertThat(effect.changed).isEmpty()
            assertThat(effect.cycles).containsExactly(listOf("acme.parquet", "acme:store:1.0"))
            assertThat(effect.baseCycles).isEmpty()
            assertThat(effect.newCycles).containsExactly(listOf("acme.parquet", "acme:store:1.0"))
        }

        @Test
        fun `the base model is unchanged`() {
            assertThat(r.hierarchy.parentOf(r.node("com.acme.store.parquet"))).isSameAs(r.node("com.acme.store", "acme:store:1.0"))
            assertThat(r.hierarchy.localNodes).isEmpty()
            assertThat(extract.model).isNotSameAs(r.model)
            assertThat(extract.report.findings).isEmpty()
        }
    }

    @Nested
    inner class LaterMovesOfEmptiedPackages {

        @Test
        fun `a sub-package selected after its parent was renamed moves with its types`() {
            val v = view(
                listOf("x"),
                move(pkg("com.acme.store"), "acme:store:1.0/com.acme.legacy"),
                move(pkg("com.acme.store.parquet"), "x"),
            )
            val h = v.model.hierarchy

            assertThat(r.path(r.node("com.acme.store.parquet.Reader", h = h), h).first()).isEqualTo("x")
            assertThat(r.path(r.node("com.acme.store.model.Node", h = h), h)).contains("com.acme.legacy.store.model")
            assertThat(codes(v)).doesNotContain(Code.VIRTUAL_MODULE_EMPTY, Code.MOVE_OF_MERGED_PACKAGE)
        }

        @Test
        fun `the same moves in the other order give the same placement`() {
            val renameFirst = view(
                listOf("x"),
                move(pkg("com.acme.store"), "acme:store:1.0/com.acme.legacy"),
                move(pkg("com.acme.store.parquet"), "x"),
            )
            val moveFirst = view(
                listOf("x"),
                move(pkg("com.acme.store.parquet"), "x"),
                move(pkg("com.acme.store"), "acme:store:1.0/com.acme.legacy"),
            )

            fun module(v: MaterializedView, fqn: String) = r.path(r.node(fqn, h = v.model.hierarchy), v.model.hierarchy).first()
            for (type in listOf("com.acme.store.parquet.Reader", "com.acme.store.parquet.Writer", "com.acme.store.detail.Query")) {
                assertThat(module(renameFirst, type)).describedAs(type).isEqualTo(module(moveFirst, type))
            }
        }

        @Test
        fun `a sub-package moved back after its package stays in its module`() {
            val v = view(
                listOf("x"),
                move(pkg("com.acme.store"), "x"),
                move(pkg("com.acme.store.parquet"), "acme:store:1.0"),
            )
            val h = v.model.hierarchy

            assertThat(r.path(r.node("com.acme.store.Store", h = h), h).first()).isEqualTo("x")
            assertThat(r.path(r.node("com.acme.store.parquet.Reader", h = h), h).first()).isEqualTo("acme:store:1.0")
            assertThat(v.report.hasErrors).isFalse()
        }

        @Test
        fun `a package merged into an existing package of the graph is not moved again`() {
            val v = view(
                emptyList(),
                move("""{ "match": "acme:mapping:1.0", "kind": "java.module" }""", "acme:store:1.0"),
                move(pkg("com.acme", "acme:mapping:1.0"), "acme:server:1.0"),
            )

            val finding = v.report.findings.single { it.code == Code.MOVE_OF_MERGED_PACKAGE }
            assertThat(finding.group).isEqualTo("moves[1]")
            assertThat(finding.message).contains("merged into the existing package com.acme", "by moves[0]")
            // the store's own com.acme stays where it is
            assertThat(r.path(r.node("com.acme.store.Store", h = v.model.hierarchy), v.model.hierarchy).first()).isEqualTo("acme:store:1.0")
        }
    }

    @Nested
    inner class ExternalTypes {

        @Test
        fun `external packages cannot be moved`() {
            val v = view(listOf("x"), move("""{ "match": "org.slf4j", "kind": "java.package" }""", "x"))

            assertThat(codes(v)).contains(Code.CANNOT_MOVE)
            assertThat(r.path(r.node("org.slf4j.Logger", h = v.model.hierarchy), v.model.hierarchy).first()).isEqualTo("External Types")
        }

        @Test
        fun `External Types is not a move target, so no cycle can run through it`() {
            val v = view(emptyList(), move(pkg("com.acme.store.parquet"), "External Types"))

            val finding = v.report.findings.single { it.code == Code.UNKNOWN_MOVE_TARGET }
            assertThat(finding.message).contains("not scanned")
            assertThat(v.effect!!.cycles.flatten()).doesNotContain("External Types")
        }
    }

    @Nested
    inner class NewCycles {

        private fun effect(cycles: List<List<String>>, baseCycles: List<List<String>>) =
            RestructuringEffect(emptyList(), emptyList(), emptyList(), cycles, baseCycles)

        @Test
        fun `a base cycle that a move shrinks or leaves alone is not new`() {
            val base = listOf(listOf("a", "b", "c"))

            assertThat(effect(listOf(listOf("a", "b")), base).newCycles).isEmpty()
            assertThat(effect(base, base).newCycles).isEmpty()
        }

        @Test
        fun `a base cycle that gains a module, or merges with another, is new`() {
            val base = listOf(listOf("a", "b"), listOf("c", "d"))

            assertThat(effect(listOf(listOf("a", "b", "virtual")), base).newCycles).containsExactly(listOf("a", "b", "virtual"))
            assertThat(effect(listOf(listOf("a", "b", "c", "d")), base).newCycles).containsExactly(listOf("a", "b", "c", "d"))
        }
    }

    @Test
    fun `moving the dependent package along removes the cycle and shows the dependencies that move with the code`() {
        val view = view(
            listOf("acme.parquet"),
            move(pkg("com.acme.store.parquet"), "acme.parquet"),
            move(pkg("com.acme.store.detail"), "acme.parquet"),
        )
        val effect = view.effect!!

        assertThat(effect.cycles).isEmpty()
        assertThat(effect.added).containsExactlyInAnyOrder(
            ModuleDependency("acme.parquet", "acme:store:1.0", 6),
            ModuleDependency("acme:server:1.0", "acme.parquet", 2),
        )
        assertThat(effect.changed).containsExactly(ChangedModuleDependency("acme:server:1.0", "acme:store:1.0", 3, 1))
        // both packages share the virtual path in the new module
        val store = r.node("com.acme.store", "acme.parquet", view.model.hierarchy)
        assertThat(r.children(store, view.model.hierarchy)).containsExactly("com.acme.store.detail", "com.acme.store.parquet")
    }

    @Test
    fun `a type moves into the package of its own package name in the target module, its nested types with it`() {
        val view = view(listOf("acme.io"), move("""{ "match": "com.acme.store.parquet.Reader", "kind": "types" }""", "acme.io"))
        val h = view.model.hierarchy
        val reader = r.node("com.acme.store.parquet.Reader")
        val page = r.node("com.acme.store.parquet.Reader\$Page")

        assertThat(r.path(reader, h)).containsExactly("acme.io", "com", "com.acme", "com.acme.store", "com.acme.store.parquet", "com.acme.store.parquet.Reader")
        assertThat(h.parentOf(page)).isSameAs(h.parentOf(reader))
        assertThat(r.children(r.node("com.acme.store.parquet", "acme:store:1.0", h), h)).containsExactly("com.acme.store.parquet.Writer")
        assertThat(codes(view)).containsExactly(Code.SPLIT_PACKAGE)
        assertThat(view.report.infos.single().message).contains("com.acme.store.parquet", "acme.io", "acme:store:1.0")
    }

    @Test
    fun `a nested type a move selects on its own does not follow its enclosing type`() {
        val view = view(
            listOf("acme.io", "acme.pages"),
            move("""{ "match": "com.acme.store.parquet.Reader", "kind": "types" }""", "acme.io"),
            move("""{ "match": "com.acme.store.parquet.Reader${'$'}Page", "kind": "types" }""", "acme.pages"),
        )
        val h = view.model.hierarchy

        assertThat(r.path(r.node("com.acme.store.parquet.Reader\$Page"), h).first()).isEqualTo("acme.pages")
        assertThat(r.path(r.node("com.acme.store.parquet.Reader"), h).first()).isEqualTo("acme.io")
    }

    @Test
    fun `a type moves into a named package, created when missing`() {
        val view = view(emptyList(), move("""{ "match": "com.acme.server.Helper", "kind": "types" }""", "acme:server:*/com.acme.server.util"))
        val h = view.model.hierarchy

        assertThat(r.path(r.node("com.acme.server.Helper"), h))
            .containsExactly("acme:server:1.0", "com", "com.acme", "com.acme.server", "com.acme.server.util", "com.acme.server.Helper")
        assertThat(ViewNodes.isVirtual(h.parentOf(r.node("com.acme.server.Helper"))!!)).isTrue()
    }

    @Test
    fun `a package moved into a package becomes its sub-package under a new name, the old package leaves the view`() {
        val view = view(emptyList(), move(pkg("com.acme.store.detail"), "acme:store:*/com.acme.store.parquet"))
        val h = view.model.hierarchy
        val query = r.node("com.acme.store.detail.Query")

        assertThat(r.path(query, h).dropLast(1).takeLast(2)).containsExactly("com.acme.store.parquet", "com.acme.store.parquet.detail")
        assertThat(h.lookupNode(r.node("com.acme.store.detail").identifier)).isNull()
        assertThat(h.lookupNode(query.identifier)).isSameAs(query)
    }

    @Test
    fun `merging a module moves its packages in, joins same-named packages and removes it`() {
        val view = view(emptyList(), move("\"acme:mapping:*\"", "acme:server:*"))
        val h = view.model.hierarchy
        val server = r.module("acme:server:1.0", h)

        assertThat(h.childrenOf(h.rootNode).map { NodeMetadata.getQualifiedName(it) }).doesNotContain("acme:mapping:1.0")
        assertThat(h.lookupNode(r.module("acme:mapping:1.0").identifier)).isNull()
        // com and com.acme existed in both: one of each remains, with the mapping package joined in
        assertThat(r.children(server, h)).containsExactly("com")
        assertThat(r.children(r.node("com.acme", "acme:server:1.0", h), h)).containsExactly("com.acme.mapping", "com.acme.server")
        assertThat(view.effect!!.removed).containsExactly(ModuleDependency("acme:mapping:1.0", "acme:store:1.0", 1))
        assertThat(view.effect!!.changed).containsExactly(ChangedModuleDependency("acme:server:1.0", "acme:store:1.0", 3, 4))
        assertThat(view.report.findings).isEmpty()
    }

    @Test
    fun `a package that leaves its parent empty takes the empty packages above it out of the view`() {
        val view = view(listOf("acme.mapping2"), move(pkg("com.acme.mapping", "acme:mapping:1.0"), "acme.mapping2"))
        val h = view.model.hierarchy

        val mapping = r.module("acme:mapping:1.0", h)
        assertThat(h.childrenOf(mapping)).isEmpty()
        assertThat(codes(view)).containsExactly(Code.REAL_MODULE_EMPTY)
    }

    @Test
    fun `groups apply to the restructured hierarchy and can take virtual modules`() {
        val view = view(
            listOf("acme.parquet"),
            move(pkg("com.acme.store.parquet"), "acme.parquet"),
            groups = """[ { "name": "storage", "members": ["acme:store:*", "acme.parquet"] } ]""",
        )
        val h = view.model.hierarchy
        val storage = view.groupNodes.getValue("storage")

        assertThat(r.children(storage, h)).containsExactly("acme.parquet", "acme:store:1.0")
        assertThat(view.effect!!.newCycles).hasSize(1)
    }

    @Test
    fun `the same document gives the same structure and handles every time`() {
        val moves = arrayOf(move(pkg("com.acme.store.parquet"), "acme.parquet"), move("""{ "match": "com.acme.server.Helper", "kind": "types" }""", "acme.parquet"))
        val first = view(listOf("acme.parquet"), *moves)
        val second = view(listOf("acme.parquet"), *moves)

        fun shape(v: MaterializedView) = v.model.hierarchy.descendantsOf(v.model.hierarchy.rootNode)
            .map { it.identifier to v.model.hierarchy.parentOf(it)?.identifier }
        assertThat(shape(second)).isEqualTo(shape(first))
    }

    @Nested
    inner class Report {

        @Test
        fun `a move that selects nothing is a warning`() {
            val view = view(listOf("x"), move("\"com.acme.nothing\"", "x"))

            assertThat(codes(view)).containsExactly(Code.MOVE_SELECTS_NOTHING, Code.VIRTUAL_MODULE_EMPTY)
            assertThat(view.report.findings.first().group).isEqualTo("moves[0]")
        }

        @Test
        fun `an unknown or ambiguous target skips the move`() {
            val unknown = view(emptyList(), move(pkg("com.acme.store.parquet"), "acme:nowhere:*"))
            val ambiguous = view(emptyList(), move(pkg("com.acme.store.parquet"), "acme:*:1.0"))

            assertThat(codes(unknown)).containsExactly(Code.UNKNOWN_MOVE_TARGET)
            assertThat(codes(ambiguous)).containsExactly(Code.AMBIGUOUS_MOVE_TARGET)
            assertThat(ambiguous.report.findings.single().nodes).hasSize(3)
            assertThat(unknown.model.hierarchy.parentOf(r.node("com.acme.store.parquet"))).isSameAs(r.node("com.acme.store", "acme:store:1.0"))
        }

        @Test
        fun `a node two moves select goes with the first`() {
            val view = view(
                listOf("a", "b"),
                move(pkg("com.acme.store.parquet"), "a"),
                move(pkg("com.acme.store.parquet"), "b"),
            )

            assertThat(codes(view)).containsExactly(Code.NODE_MOVED_TWICE, Code.VIRTUAL_MODULE_EMPTY)
            assertThat(r.path(r.node("com.acme.store.parquet"), view.model.hierarchy).first()).isEqualTo("a")
        }

        @Test
        fun `a package cannot move into its own subtree`() {
            val view = view(emptyList(), move(pkg("com.acme.store"), "acme:store:*/com.acme.store.parquet"))

            assertThat(codes(view)).containsExactly(Code.MOVE_INTO_OWN_SUBTREE)
            val store = r.node("com.acme.store", "acme:store:1.0")
            assertThat(view.model.hierarchy.parentOf(store)).isSameAs(r.node("com.acme", "acme:store:1.0"))
        }

        @Test
        fun `members, external types and a module into a package cannot be moved`() {
            val external = view(listOf("x"), move("""{ "match": "org.slf4j.Logger", "kind": "external.type" }""", "x"))
            val module = view(emptyList(), move("\"acme:mapping:*\"", "acme:server:*/com.acme.server"))

            assertThat(codes(external)).containsExactly(Code.CANNOT_MOVE, Code.VIRTUAL_MODULE_EMPTY)
            assertThat(codes(module)).containsExactly(Code.CANNOT_MOVE)
            assertThat(module.report.findings.single().message).contains("only be merged into a module")
        }

        @Test
        fun `a virtual handle taken by a graph node keeps the whole view from being applied`() {
            val squatter = HGGraphFactory.createNode(r.model.coreGraph as HGGraphImpl) {
                SyntheticNodeSource(ViewNodes.moduleHandle("v", "acme.parquet"), "squatter", "acme.Squatter")
            }
            HierarchyFactory.addChild(r.hierarchy, r.node("com.acme.server", "acme:server:1.0"), squatter)

            val view = view(listOf("acme.parquet"), move(pkg("com.acme.store.parquet"), "acme.parquet"))

            assertThat(codes(view)).containsExactly(Code.VIRTUAL_HANDLE_COLLISION)
            assertThat(view.model).isSameAs(r.model)
            assertThat(view.virtualModules).isEmpty()
        }
    }
}
