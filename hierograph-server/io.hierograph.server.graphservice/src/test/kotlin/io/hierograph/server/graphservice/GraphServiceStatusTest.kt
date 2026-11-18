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

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.server.mapping.GraphStoreToHGModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The typed status the graph service reports: the cheap snapshot status, the cached node counts and the state. */
class GraphServiceStatusTest {

    private fun model(vararg classes: Pair<String, JavaNodeKind>): HGModel {
        val store = GraphStore().apply {
            registerArtifact(ArtifactInfo("billing-core", "billing-core", "billing-core"))
            for ((fqn, kind) in classes) {
                applyClass("billing-core", ClassFacts(fqn, kind, 0, emptyList(), classHash = fqn), emptyList())
            }
        }
        return GraphStoreToHGModel.build(store)
    }

    private val service = HierarchicalGraphService()

    @Test
    fun `without a graph there is no snapshot, no counts, and the graph is unavailable`() {
        assertThat(service.snapshot()).isNull()
        assertThat(service.nodeCounts()).isNull()
        assertThat(service.snapshotStatus()).isNull()
        assertThat(service.graphState()).isEqualTo(GraphState.UNAVAILABLE)
    }

    @Test
    fun `a loaded graph reports its generation, hash, load time and state`() {
        service.seed(model("com.example.Invoice" to JavaNodeKind.CLASS))

        val snapshot = service.snapshot()!!
        assertThat(snapshot.dataHash).isEqualTo(service.dataHash)
        assertThat(snapshot.loadedAt).isNotNull()
        assertThat(service.graphState()).isEqualTo(GraphState.LOADED) // a seeded service has no updater
    }

    @Test
    fun `node counts count modules, packages and scanned types`() {
        service.seed(model(
            "com.example.Invoice" to JavaNodeKind.CLASS,
            "com.example.Billable" to JavaNodeKind.INTERFACE,
            "com.example.tax.Rate" to JavaNodeKind.RECORD,
        ))

        val counts = service.nodeCounts()!!
        assertThat(counts.modules).isEqualTo(1)
        assertThat(counts.packages).isEqualTo(3) // com, com.example and com.example.tax; not the External Types packages
        assertThat(counts.types).isEqualTo(3)
        assertThat(counts.total).isGreaterThanOrEqualTo(counts.modules + counts.packages + counts.types)
        assertThat(service.snapshotStatus()!!["total_nodes"]).isEqualTo(counts.total)
    }

    @Test
    fun `node counts are computed once per graph and again for the next one`() {
        service.seed(model("com.example.Invoice" to JavaNodeKind.CLASS))
        val first = service.nodeCounts()
        assertThat(service.nodeCounts()).isSameAs(first)

        service.seed(model("com.example.Invoice" to JavaNodeKind.CLASS, "com.example.Order" to JavaNodeKind.CLASS))
        val next = service.nodeCounts()!!
        assertThat(next).isNotSameAs(first)
        assertThat(next.types).isEqualTo(2)
    }

    @Test
    fun `a newer graph is a later generation and a later load`() {
        service.seed(model("com.example.Invoice" to JavaNodeKind.CLASS))
        val first = service.snapshot()!!
        service.seed(model("com.example.Order" to JavaNodeKind.CLASS))
        val next = service.snapshot()!!

        assertThat(next.dataHash).isNotEqualTo(first.dataHash)
        assertThat(next.loadedAt).isAfterOrEqualTo(first.loadedAt)
    }

    @Test
    fun `the source settings render as before`() {
        assertThat(service.graphSourceSettings()).isEqualTo(GraphSourceSettings(kind = null))
        assertThat(service.sourceSettings()).isEqualTo(mapOf("kind" to null))
    }
}
