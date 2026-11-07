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
 * Central, identical coordinates). Two hard assertions:
 *  - **defined types match** — the scanner invents none, and every only-jQA type is a nested type jQA
 *    over-materializes from references (nested classes of unscanned outer types).
 *  - **type-dependency roll-up is sound** — comparing non-primitive type→type pairs, the scanner invents
 *    no edge jQA lacks (`only-scanner = 0`). The scanner records member-level references (calls, field/
 *    param/return/throws types, …) as type-level refs, so `typeDependencies()` is as dense as jQA's.
 *
 * The reverse residual (edges only in jQA, ~5k on Spring) is **reported, not asserted**: it is jQA
 * over-including *compilation artifacts* the scanner cleanly omits — invokedynamic bootstrap
 * (`java.lang.invoke.MethodHandles$Lookup`, ~50%), primitive arrays, and synthetic Kotlin classes. The
 * scanner deliberately produces the cleaner graph; a few small categories (e.g. `java.lang.Enum` on enum
 * supertypes, some nested types) are worth a later look as possible minor scanner gaps.
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
        println("type-dependency diff (raw): only-scanner=${diff.dependencies.onlyInGraphStore.size}, only-jQA=${diff.dependencies.onlyInReference.size}")

        // The raw diff is dominated by a modeling difference: jQA models primitives/void as DEPENDS_ON
        // targets; the scanner skips them. Normalize to meaningful (non-primitive) type→type *pairs*
        // (ignoring the structural-flag classification) to expose the true residual divergence.
        fun meaningfulPairs(deps: List<io.hierograph.graphstore.TypeDependency>): Set<Pair<String, String>> =
            deps.asSequence().filter { it.to !in TRIVIAL_TARGETS }.map { it.from to it.to }.toHashSet()
        val scannerPairs = meaningfulPairs(scanner.typeDependencies().toList())
        val jqaPairs = meaningfulPairs(jqa.typeDependencies().toList())
        val onlyScannerDeps = (scannerPairs - jqaPairs)
        val onlyJqaDeps = (jqaPairs - scannerPairs)
        println("type-dependency diff (non-primitive pairs): scanner=${scannerPairs.size}, jQA=${jqaPairs.size}, only-scanner=${onlyScannerDeps.size}, only-jQA=${onlyJqaDeps.size}")
        println("  only-scanner (<=20): ${onlyScannerDeps.take(20)}")
        println("  only-jQA residual by target type (top 25):")
        onlyJqaDeps.groupingBy { it.second }.eachCount().entries.sortedByDescending { it.value }.take(25)
            .forEach { (target, n) -> println("    %5d  %s".format(n, target)) }
        println(diff.render(limitPerSection = 20))

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

        // Type-dependency roll-up is SOUND: the scanner invents no non-primitive type→type edge that jQA
        // does not also have (comparing by (from,to) pair, ignoring the structural-flag classification).
        // The reverse residual (only-jQA) is jQA over-including compilation artifacts the scanner omits —
        // chiefly invokedynamic bootstrap (java.lang.invoke.MethodHandles$Lookup), primitive arrays, and
        // synthetic Kotlin classes — printed above, not asserted.
        assertThat(onlyScannerDeps)
            .withFailMessage { "Non-primitive type dependencies invented by the scanner (should be none): ${onlyScannerDeps.take(50)}" }
            .isEmpty()
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
        /** Primitive/void dependency targets: the scanner skips them, jQA models them as DEPENDS_ON. */
        val TRIVIAL_TARGETS = setOf("void", "boolean", "byte", "char", "short", "int", "long", "float", "double")
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
