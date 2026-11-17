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
package io.hierograph.server.mapping

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GraphStoreToHGModelTest {

    private lateinit var store: GraphStore

    /** Nodes are identified by the content-addressed handle of their GraphStore key; address them via this. */
    private fun h(key: String): Long = NodeHandle.of(key)

    @BeforeEach
    fun setUp() {
        store = GraphStore()
        store.registerArtifact(ArtifactInfo("app", "app", "app"))
        store.applyClass("app", ClassFacts("com.foo.Base", JavaNodeKind.CLASS, 0, emptyList(), "h1"), emptyList())
        store.applyClass(
            "app",
            ClassFacts(
                "com.foo.Sub", JavaNodeKind.CLASS, 0, emptyList(),
                classHash = "h2",
            ),
            listOf(
                Ref("com.foo.Base", DependencyKind.EXTENDS, count = 1),
                Ref("java.util.List", DependencyKind.CALLS, count = 3),
            ),
        )
    }

    @Test
    fun `builds an HGModel with the same nodes, hierarchy and kinds`() {
        val model = GraphStoreToHGModel.build(store)

        // every GraphStore node + the synthetic root
        assertThat(model.coreGraph.nodes).hasSize(store.nodes().size + 1)

        // top-level modules hang under the root: the app module and the External Types module
        val rootChildren = model.hierarchy.childrenOf(model.hierarchy.rootNode).map { it.identifier }
        // Modules and packages are keyed directly; types by their artifact-scoped key.
        assertThat(rootChildren).contains(h("app"), h(GraphStore.EXTERNAL_ARTIFACT_KEY))

        // the package chain and type are navigable, with kinds carried over
        val sub = model.lookupNode(h(store.typeKey("app", "com.foo.Sub")))!!
        assertThat(sub.kind).isEqualTo(JavaNodeKind.CLASS)
        assertThat(model.hierarchy.parentOf(sub)!!.identifier).isEqualTo(h("pkg:app:com.foo"))
        assertThat(model.lookupNode(h("pkg:app:com.foo"))!!.kind).isEqualTo(JavaNodeKind.PACKAGE)

        // the unresolved target is a placeholder external type, scoped to the external module
        assertThat(model.lookupNode(h(store.typeKey(GraphStore.EXTERNAL_ARTIFACT_KEY, "java.util.List")))!!.kind)
            .isEqualTo(JavaNodeKind.EXTERNAL_TYPE)
    }

    @Test
    fun `carries type dependencies with weight and attribute bitmap`() {
        val model = GraphStoreToHGModel.build(store)
        val sub = model.lookupNode(h(store.typeKey("app", "com.foo.Sub")))!!

        val toBase = sub.outgoingCoreDependencies.single { it.to.identifier == h(store.typeKey("app", "com.foo.Base")) }
        assertThat(JavaEdgeAttributes.isSet(toBase.attributesBitmap, JavaEdgeAttributes.IS_EXTENDS)).isTrue
        assertThat(JavaEdgeAttributes.isSet(toBase.attributesBitmap, JavaEdgeAttributes.IS_DEPENDS_ON_OTHER)).isFalse

        val toList = sub.outgoingCoreDependencies.single {
            it.to.identifier == h(store.typeKey(GraphStore.EXTERNAL_ARTIFACT_KEY, "java.util.List"))
        }
        assertThat(toList.weight).isEqualTo(3)
        assertThat(JavaEdgeAttributes.isSet(toList.attributesBitmap, JavaEdgeAttributes.IS_DEPENDS_ON_OTHER)).isTrue

        // the edge shows up as incoming on Base
        assertThat(model.lookupNode(h(store.typeKey("app", "com.foo.Base")))!!.incomingCoreDependencies.map { it.from.identifier })
            .contains(h(store.typeKey("app", "com.foo.Sub")))
    }

    @Test
    fun `a dependency source is identified by its endpoints' handles and carries the kind counts`() {
        val model = GraphStoreToHGModel.build(store)
        val sub = h(store.typeKey("app", "com.foo.Sub"))
        val base = h(store.typeKey("app", "com.foo.Base"))
        val toBase = model.lookupNode(sub)!!.outgoingCoreDependencies.single { it.to.identifier == base }

        val source = toBase.getDependencySource(GraphStoreDependencySource::class.java)!!
        assertThat(source.identifier).isEqualTo(sub to base)
        assertThat(source.dependency).isSameAs(toBase)
        assertThat(source.kinds).isEqualTo(mapOf(DependencyKind.EXTENDS to 1))
        assertThat(GraphStoreDependencySource.kindsOf(toBase)).isEqualTo(mapOf(DependencyKind.EXTENDS to 1))
    }

    @Test
    fun `dependencies with the same kind counts share one read-only map`() {
        // A second subclass of Base with the same facts: {EXTENDS=1} twice, {CALLS=3} once.
        store.applyClass(
            "app",
            ClassFacts("com.foo.Other", JavaNodeKind.CLASS, 0, emptyList(), classHash = "h3"),
            listOf(Ref("com.foo.Base", DependencyKind.EXTENDS, count = 1)),
        )
        val model = GraphStoreToHGModel.build(store)
        val base = h(store.typeKey("app", "com.foo.Base"))
        fun kindsTo(fqn: String) = GraphStoreDependencySource.kindsOf(
            model.lookupNode(h(store.typeKey("app", fqn)))!!.outgoingCoreDependencies.single { it.to.identifier == base },
        )

        val fromSub = kindsTo("com.foo.Sub")
        val fromOther = kindsTo("com.foo.Other")
        assertThat(fromSub).isEqualTo(mapOf(DependencyKind.EXTENDS to 1))
        assertThat(fromOther).isSameAs(fromSub)
        @Suppress("UNCHECKED_CAST")
        val mutable = fromSub as MutableMap<DependencyKind, Int>
        assertThatThrownBy { mutable[DependencyKind.CALLS] = 1 }.isInstanceOf(UnsupportedOperationException::class.java)
    }
}
