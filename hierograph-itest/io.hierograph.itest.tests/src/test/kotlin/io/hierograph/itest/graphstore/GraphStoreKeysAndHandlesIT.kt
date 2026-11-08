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
package io.hierograph.itest.graphstore

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.parquet.ParquetReader
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.itest.fwk.SpringFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import kotlin.time.measureTimedValue

/**
 * Artifact-scoped keys and content-addressed [NodeHandle] stability over the real Spring snapshot. The
 * unit tests cover these on synthetic 1–2 class graphs; here they are exercised across the full 21-jar set.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphStoreKeysAndHandlesIT {

    private val dir = SpringFixture.directory
    private val log = LoggerFactory.getLogger(GraphStoreKeysAndHandlesIT::class.java)

    private fun freshStore(): GraphStore {
        val (store, elapsed) = measureTimedValue { GraphStore().also { ParquetReader().load(dir, it) } }
        log.info("freshStore: loaded GraphStore ({} nodes) from the snapshot in {}", store.nodeCount(), elapsed)
        return store
    }

    @Test
    fun `type node keys are artifact-scoped and end with the fqn`() {
        val node = freshStore().typeByFqn("org.springframework.core.Ordered")!!
        // type:<len>:<artifactKey>:<fqn>
        assertThat(node.key).startsWith("type:").endsWith(":org.springframework.core.Ordered")
    }

    @Test
    fun `loading the same snapshot twice yields the identical node-key set (deterministic ingestion)`() {
        val keys1 = freshStore().nodes().map { it.key }.toSortedSet()
        val keys2 = freshStore().nodes().map { it.key }.toSortedSet()
        assertThat(keys1).isEqualTo(keys2)
    }

    @Test
    fun `every node handle is a deterministic JSON-safe 53-bit int`() {
        val keys = freshStore().nodes().map { it.key }
        assertThat(keys).isNotEmpty
        for (k in keys.asSequence().take(5_000)) {
            val h = NodeHandle.of(k)
            assertThat(h).isEqualTo(NodeHandle.of(k))         // deterministic
            assertThat(h).isBetween(0L, (1L shl 53) - 1)      // JSON/JS-safe
        }
    }

    @Test
    fun `each Spring FQN resolves to a single artifact-scoped type (no cross-jar duplicates)`() {
        val store = freshStore()
        for (fqn in listOf(
            "org.springframework.core.io.Resource",
            "org.springframework.beans.factory.BeanFactory",
            "org.springframework.web.bind.annotation.RequestMapping",
        )) {
            assertThat(store.typesWithFqn(fqn)).describedAs(fqn).hasSize(1)
        }
    }
}
