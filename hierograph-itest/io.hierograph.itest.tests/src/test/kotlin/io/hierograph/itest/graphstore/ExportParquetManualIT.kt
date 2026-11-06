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

import dev.hardwood.metadata.CompressionCodec
import io.hierograph.boltclient.IBoltClientFactory
import io.hierograph.graphstore.jqa.JqaParquetExporter
import io.hierograph.graphstore.jqa.detail.JqaDetailExporter
import io.hierograph.itest.fwk.AbstractIntegrationTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Manual export tool (not a normal part of the build): writes the Parquet files for the integration-test
 * jQAssistant database to a directory you choose, so the snapshot can be inspected on disk (DuckDB,
 * parquet-tools, Hardwood CLI, …).
 *
 * It is **gated on — and located by — the `hierograph.export.dir` system property**: when that property
 * is set the test runs and writes there; when it is absent (every ordinary build) JUnit skips it. Run it
 * from the repo root with:
 *
 * ```
 * mvn -Pitest -pl hierograph-itest/io.hierograph.itest.tests -am verify \
 *     -Dit.test=ExportParquetManualIT -Dhierograph.export.dir=$PWD/jqa-parquet-export
 * ```
 *
 * (Needs Docker — it uses the shared Testcontainers jQAssistant image. `jqa-parquet-export/` is
 * git-ignored.)
 */
@EnabledIfSystemProperty(named = "hierograph.export.dir", matches = ".+")
class ExportParquetManualIT : AbstractIntegrationTest() {

    @Test
    fun `export the jQA test database to Parquet files`() {
        val outDir = Paths.get(System.getProperty("hierograph.export.dir")).toAbsolutePath()
        Files.createDirectories(outDir)

        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val boltClient = IBoltClientFactory.newInstance(executor).createBoltClient(container.boltUri)
        boltClient.connect()
        try {
            // Type-level tables (types/members/refs) and the member-level detail_refs table, ZSTD.
            JqaParquetExporter(boltClient).exportTo(outDir, CompressionCodec.ZSTD)
            JqaDetailExporter(boltClient).exportTo(outDir, CompressionCodec.ZSTD)
        } finally {
            boltClient.disconnect()
            executor.shutdownNow()
        }

        println("=== exported jQA database to $outDir ===")
        Files.list(outDir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".parquet") }.sorted().forEach { p ->
                println(String.format(Locale.ROOT, "  %-20s %,12d bytes", p.fileName, Files.size(p)))
            }
        }
    }
}
