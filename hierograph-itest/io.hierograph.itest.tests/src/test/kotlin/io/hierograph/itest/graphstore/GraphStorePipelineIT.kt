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

import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.parquet.HierographArchive
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.itest.fwk.SpringFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path

/**
 * End-to-end pipeline checks over the real Spring 7.0.8 snapshot produced by the ASM scanner
 * ([SpringFixture]): the on-disk per-artifact `.hierograph` archive set, and a full load into a
 * [GraphStore]. These exercise the scanner → Parquet → GraphStore path at real scale (21 jars),
 * complementing the unit tests that run on 1–2 synthetic classes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GraphStorePipelineIT {

    private lateinit var dir: Path
    private lateinit var store: GraphStore

    @BeforeAll
    fun load() {
        dir = SpringFixture.directory
        store = GraphStore().also { ParquetReader().load(dir, it) }
    }

    @Test
    fun `the snapshot is one hierograph archive per scanned jar`() {
        val archives = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(HierographArchive.EXTENSION) }.toList() }
        assertThat(archives).hasSize(SpringFixture.SPRING_MODULES.size) // 21
        val names = archives.map { it.fileName.toString() }
        assertThat(names).contains("spring-core-7.0.8.hierograph", "spring-context-7.0.8.hierograph", "spring-web-7.0.8.hierograph")
    }

    @Test
    fun `a full load materializes a large graph with the 21 modules plus the external module`() {
        val modules = store.nodes().filter { it.kind.toString() == "java.module" }
        // 21 scanned jars + the synthetic "External Types" module.
        assertThat(modules.map { it.name }).contains(GraphStore.EXTERNAL_ARTIFACT_NAME)
        assertThat(modules).hasSize(SpringFixture.SPRING_MODULES.size + 1)
        assertThat(store.nodeCount()).isGreaterThan(50_000) // packages + types + members across 21 jars
    }

    @Test
    fun `known Spring types are loaded as real (non-placeholder) nodes`() {
        for (fqn in listOf(
            "org.springframework.core.Ordered",
            "org.springframework.core.io.Resource",
            "org.springframework.context.annotation.Configuration",
        )) {
            val node = store.typeByFqn(fqn)
            assertThat(node).describedAs(fqn).isNotNull
            assertThat(node!!.placeholder).describedAs("$fqn placeholder").isFalse
        }
    }

    @Test
    fun `members are materialized across the whole set`() {
        // Every method/field in all 21 jars is a member node; read the keys straight off the archives.
        assertThat(ParquetReader().readMemberKeys(dir).size).isGreaterThan(50_000)
    }

    @Test
    fun `no module-info pseudo-type is modeled`() {
        assertThat(store.nodes().none { it.fqn.contains("module-info") }).isTrue()
    }
}
