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
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.Origin
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.model.RefKind
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.graphstore.jvm.model.JavaEdgeAttributes
import io.hierograph.graphstore.jvm.model.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GraphStoreToHGModelTest {

    private lateinit var store: GraphStore

    /** Nodes are identified by the content-addressed handle of their GraphStore key; address them via this. */
    private fun h(key: String): Long = NodeHandle.of(key)

    @BeforeEach
    fun setUp() {
        store = GraphStore()
        store.registerArtifact(ArtifactInfo("app", "app", "app", Origin.MODULE_MAIN))
        store.applyClass("app", ClassFacts("com.foo.Base", JavaKinds.CLASS, 0, emptyList(), emptyList(), "h1"))
        store.applyClass(
            "app",
            ClassFacts(
                "com.foo.Sub", JavaKinds.CLASS, 0, emptyList(),
                refs = listOf(
                    Ref("com.foo.Sub", "com.foo.Base", RefKind.EXTENDS, count = 1),
                    Ref("com.foo.Sub", "java.util.List", RefKind.DEPENDS_ON, count = 3),
                ),
                classHash = "h2",
            ),
        )
    }

    @Test
    fun `builds an HGModel with the same nodes, hierarchy and kinds`() {
        val model = GraphStoreToHGModel.build(store)

        // every GraphStore node + the synthetic root
        assertThat(model.coreGraph.nodes).hasSize(store.nodeCount() + 1)

        // top-level modules hang under the root: the app module and the External Types module
        val rootChildren = model.hierarchy.childrenOf(model.hierarchy.rootNode).map { it.identifier }
        // Modules and packages are keyed directly; types by their artifact-scoped key.
        assertThat(rootChildren).contains(h("app"), h(GraphStore.EXTERNAL_ARTIFACT_KEY))

        // the package chain and type are navigable, with kinds carried over
        val sub = model.lookupNode(h(store.typeKey("app", "com.foo.Sub")))!!
        assertThat(sub.kind).isEqualTo(JavaKinds.CLASS)
        assertThat(model.hierarchy.parentOf(sub)!!.identifier).isEqualTo(h("pkg:app:com.foo"))
        assertThat(model.lookupNode(h("pkg:app:com.foo"))!!.kind).isEqualTo(JavaKinds.PACKAGE)

        // the unresolved target is a placeholder external type, scoped to the external module
        assertThat(model.lookupNode(h(store.typeKey(GraphStore.EXTERNAL_ARTIFACT_KEY, "java.util.List")))!!.kind)
            .isEqualTo(JavaKinds.EXTERNAL_TYPE)
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
}
