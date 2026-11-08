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
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.graphstore.jvm.model.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The payoff of content-addressed handles ([NodeHandle]): a node's protocol id is a pure function of its
 * stable key, so it is identical across independent builds (≈ rescans / restarts), surviving nodes keep
 * their id when the graph around them changes, and every id is a JSON-safe 53-bit integer.
 */
class GraphStoreHandleStabilityTest {

    private val artifact = "app"
    private val serviceKey = "com.foo.Service"
    private val repoKey = "com.foo.Repo"

    /** The content-addressed handle of a type's artifact-scoped GraphStore key. */
    private fun handle(artifactKey: String, fqn: String): Long =
        NodeHandle.of(GraphStore().typeKey(artifactKey, fqn))

    private fun storeWith(vararg typeKeys: String): GraphStore {
        val store = GraphStore()
        store.registerArtifact(ArtifactInfo(artifact, artifact, artifact, Origin.MODULE_MAIN))
        for (key in typeKeys) {
            store.applyClass(artifact, ClassFacts(key, JavaKinds.CLASS, 0, emptyList(), emptyList(), "h"))
        }
        return store
    }

    private fun identifiers(model: HGModel): Set<Any> {
        val hierarchy = model.hierarchy
        val ids = HashSet<Any>()
        for (top in hierarchy.childrenOf(hierarchy.rootNode)) {
            hierarchy.traverse(top) { node: HGNode -> ids.add(node.identifier) }
        }
        return ids
    }

    @Test
    fun `the same snapshot yields the same handles on every build`() {
        val ids1 = identifiers(GraphStoreToHGModel.build(storeWith(serviceKey, repoKey)))
        val ids2 = identifiers(GraphStoreToHGModel.build(storeWith(serviceKey, repoKey)))

        assertThat(ids2).isEqualTo(ids1)
        // and the handle really is the content-addressed projection of the artifact-scoped key
        assertThat(ids1).contains(handle(artifact, serviceKey), handle(artifact, repoKey))
    }

    @Test
    fun `a surviving node keeps its handle when a sibling is removed`() {
        val full = GraphStoreToHGModel.build(storeWith(serviceKey, repoKey))
        val reduced = GraphStoreToHGModel.build(storeWith(serviceKey)) // Repo deleted in the "rescan"

        // Service is addressable by the very same handle before and after; Repo's handle is simply gone.
        assertThat(full.lookupNode(handle(artifact, serviceKey))).isNotNull()
        assertThat(reduced.lookupNode(handle(artifact, serviceKey))).isNotNull()
        assertThat(full.lookupNode(handle(artifact, repoKey))).isNotNull()
        assertThat(reduced.lookupNode(handle(artifact, repoKey))).isNull()
    }

    @Test
    fun `the same FQN in two artifacts gets two distinct handles`() {
        // The whole point of artifact-scoped keys: a bare type FQN is not unique across artifacts, so the
        // two artifacts' copies are distinct nodes with distinct keys -> distinct handles.
        assertThat(handle("artifact-a", serviceKey)).isNotEqualTo(handle("artifact-b", serviceKey))
    }

    @Test
    fun `every handle is a JSON-safe 53-bit integer`() {
        val maxSafe = 0x1FFFFFFFFFFFFFL // 2^53 - 1
        val keys = listOf(GraphStore().typeKey(artifact, serviceKey), "pkg:app:com.foo", "<root>", "app")
        for (key in keys) {
            assertThat(NodeHandle.of(key)).isBetween(0L, maxSafe)
        }
    }
}
