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

import io.hierograph.boltclient.IBoltClientFactory
import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.HierarchyRow
import io.hierograph.graphstore.PlaceholderPolicy
import io.hierograph.graphstore.jqa.DiffOptions
import io.hierograph.graphstore.jqa.JqaParquetExporter
import io.hierograph.graphstore.jqa.SnapshotDiff
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.graphstore.scanner.BytecodeScanner
import io.hierograph.graphstore.scanner.ScanArtifact
import io.hierograph.itest.fwk.AbstractIntegrationTest
import io.hierograph.mcp.javaspec.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

/**
 * The trust gate for retiring jQAssistant: run the **same** Spring jars through both backends and diff
 * them through the identical GraphStore projection — the ASM scanner (`.hierograph` → GraphStore) vs the
 * jQAssistant export (Neo4j → Parquet → GraphStore). Because both sides are the same `GraphStore`
 * projection, every difference is scanner-facts vs jQA-facts.
 *
 * The jars are the 21 Spring 7.0.8 modules the container itself scanned (downloaded here from Maven
 * Central, identical coordinates), so the **set of defined types must be identical** — that is the hard
 * assertion. Dependency differences (the two models classify/weight edges differently) are reported for
 * investigation, not asserted, while we converge the scanner on jQA.
 *
 * Opt-in (needs Docker **and** network): run with
 * `mvn -Pitest -pl hierograph-itest/io.hierograph.itest.tests -am verify -Dit.test=ScannerVsJqaDiffIT -Dhierograph.scanner.validate=1`.
 */
@EnabledIfSystemProperty(named = "hierograph.scanner.validate", matches = ".+")
class ScannerVsJqaDiffIT : AbstractIntegrationTest() {

    @Test
    fun `the ASM scanner defines the same types as jQAssistant for the Spring jars`(
        @TempDir jarsDir: Path,
        @TempDir scannerDir: Path,
        @TempDir jqaDir: Path,
    ) {
        val jars = downloadSpringJars(jarsDir)
        assertThat(jars).hasSize(SPRING_MODULES.size)

        // Scanner side: scan the jars -> per-artifact .hierograph -> GraphStore (the real pipeline).
        BytecodeScanner().scan(jars.map { ScanArtifact.of(it) }).writeTo(scannerDir)
        val scanner = GraphStore(PlaceholderPolicy.JQA_ALIGNED).also { ParquetReader().load(scannerDir, it) }

        // jQA side: export the container's store of the same jars -> Parquet -> GraphStore.
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val bolt = IBoltClientFactory.newInstance(executor).createBoltClient(container.boltUri)
        bolt.connect()
        try {
            JqaParquetExporter(bolt).exportTo(jqaDir)
        } finally {
            bolt.disconnect(); executor.shutdownNow()
        }
        val jqa = GraphStore(PlaceholderPolicy.JQA_ALIGNED).also { ParquetReader().load(jqaDir, it) }

        val diff = SnapshotDiff.diff(
            scanner.hierarchyRows().toList(), scanner.typeDependencies().toList(),
            jqa.hierarchyRows().toList(), jqa.typeDependencies().toList(),
            DiffOptions(ignoreDependencyOther = true, ignoreDependencyWeight = true),
        )

        // --- report ---------------------------------------------------------------------
        println("=== scanner vs jQAssistant (same Spring 7.0.8 jars) ===")
        println("scanner hierarchy rows by kind: ${countByKind(scanner.hierarchyRows().toList())}")
        println("jQA     hierarchy rows by kind: ${countByKind(jqa.hierarchyRows().toList())}")
        val scannerTypes = definedTypes(scanner.hierarchyRows().toList())
        val jqaTypes = definedTypes(jqa.hierarchyRows().toList())
        val onlyScannerTypes = (scannerTypes - jqaTypes).sorted()
        val onlyJqaTypes = (jqaTypes - scannerTypes).sorted()
        println("defined types: scanner=${scannerTypes.size}, jQA=${jqaTypes.size}, only-scanner=${onlyScannerTypes.size}, only-jQA=${onlyJqaTypes.size}")
        if (onlyScannerTypes.isNotEmpty()) println("  only-scanner types (<=30): ${onlyScannerTypes.take(30)}")
        if (onlyJqaTypes.isNotEmpty()) println("  only-jQA types (<=30): ${onlyJqaTypes.take(30)}")
        println("type-dependency diff: only-scanner=${diff.dependencies.onlyInGraphStore.size}, only-jQA=${diff.dependencies.onlyInReference.size}")
        println(diff.render(limitPerSection = 40))

        // --- assertion: the scanner defines exactly the Spring types jQA does -----------
        // The scanner must invent nothing: every defined type it reports was really in the jars.
        assertThat(scannerTypes).isNotEmpty()
        assertThat(onlyScannerTypes)
            .withFailMessage { "Types defined only by the scanner (should be none): $onlyScannerTypes" }
            .isEmpty()
        // The two agree on every top-level type; jQA additionally materializes some *nested* types from
        // references (e.g. nested interfaces of external classes it resolved). Every only-jQA type must
        // therefore be a nested type — a top-level type only in jQA would be a real scanner gap.
        assertThat(onlyJqaTypes)
            .withFailMessage { "jQA-only *top-level* defined types (real gaps): ${onlyJqaTypes.filterNot { '$' in it }}" }
            .allMatch { '$' in it }
    }

    private fun definedTypes(rows: List<HierarchyRow>): Set<String> =
        rows.filter { JavaKinds.isTypeKind(it.kind) }.map { it.fqn }.toHashSet()

    private fun countByKind(rows: List<HierarchyRow>): Map<String, Int> =
        rows.groupingBy { (it.kind as? Enum<*>)?.name ?: it.kind.toString() }.eachCount().toSortedMap()

    private fun downloadSpringJars(dir: Path): List<Path> = SPRING_MODULES.map { module ->
        val jar = dir.resolve("$module-$SPRING_VERSION.jar")
        URI("$MAVEN_CENTRAL/org/springframework/$module/$SPRING_VERSION/$module-$SPRING_VERSION.jar").toURL()
            .openStream().use { Files.copy(it, jar) }
        jar
    }

    private companion object {
        const val SPRING_VERSION = "7.0.8"
        const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
        val SPRING_MODULES = listOf(
            "spring-aop", "spring-aspects", "spring-beans", "spring-context", "spring-context-indexer",
            "spring-context-support", "spring-core", "spring-expression", "spring-instrument", "spring-jdbc",
            "spring-jms", "spring-messaging", "spring-orm", "spring-oxm", "spring-r2dbc", "spring-test",
            "spring-tx", "spring-web", "spring-webflux", "spring-webmvc", "spring-websocket",
        )
    }
}
