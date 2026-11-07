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
package io.hierograph.itest.fwk

import io.hierograph.graphstore.scanner.BytecodeScanner
import io.hierograph.graphstore.scanner.ScanArtifact
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * The shared integration-test data source: a Spring 7.0.8 `.hierograph` snapshot produced by the ASM
 * scanner. The 21 Spring jars are downloaded from Maven Central and scanned into a temp directory **once
 * per JVM** (built lazily on first access); every integration test boots the server against this snapshot
 * via `hierograph.graph.parquetDir`. This replaces the retired jQAssistant/Neo4j Testcontainers image.
 */
object SpringFixture {

    const val SPRING_VERSION = "7.0.8"
    private const val MAVEN_CENTRAL = "https://repo1.maven.org/maven2"

    val SPRING_MODULES = listOf(
        "spring-aop", "spring-aspects", "spring-beans", "spring-context", "spring-context-indexer",
        "spring-context-support", "spring-core", "spring-expression", "spring-instrument", "spring-jdbc",
        "spring-jms", "spring-messaging", "spring-orm", "spring-oxm", "spring-r2dbc", "spring-test",
        "spring-tx", "spring-web", "spring-webflux", "spring-webmvc", "spring-websocket",
    )

    /** Absolute path of the scanner-produced `.hierograph` snapshot directory (built once, then reused). */
    val directory: Path by lazy { build() }

    private fun build(): Path {
        val base = Files.createTempDirectory("hierograph-spring-fixture")
        val jarsDir = Files.createDirectories(base.resolve("jars"))
        val snapshotDir = Files.createDirectories(base.resolve("hierograph"))
        val jars = SPRING_MODULES.map { module ->
            val jar = jarsDir.resolve("$module-$SPRING_VERSION.jar")
            URI("$MAVEN_CENTRAL/org/springframework/$module/$SPRING_VERSION/$module-$SPRING_VERSION.jar").toURL()
                .openStream().use { Files.copy(it, jar) }
            jar
        }
        BytecodeScanner().scan(jars.map { ScanArtifact.of(it) }).writeTo(snapshotDir)
        return snapshotDir
    }
}
