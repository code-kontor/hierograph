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

import java.time.Duration
import java.util.Properties
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.utility.DockerImageName

/**
 * The single jQAssistant-server container shared by all integration tests. It is started once
 * before the first test and stopped after the last test by [HierographImageExtension].
 *
 * The image coordinate is read from `itest.properties`, which Maven filters at build time so the
 * tag carries the reactor version. A `hierograph.itest.image` system property, if set, overrides
 * it (handy for testing a specific tag without rebuilding).
 */
object HierographImageContainer {

    const val HTTP_PORT: Int = 7474
    const val BOLT_PORT: Int = 7687

    private const val IMAGE_PROPERTY = "hierograph.itest.image"

    private val imageName: String = resolveImageName()

    private fun resolveImageName(): String {
        System.getProperty(IMAGE_PROPERTY)?.let { return it }
        val properties = Properties()
        checkNotNull(javaClass.getResourceAsStream("/itest.properties")) {
            "itest.properties not found on the test classpath"
        }.use { properties.load(it) }
        return checkNotNull(properties.getProperty(IMAGE_PROPERTY)) {
            "'$IMAGE_PROPERTY' missing from itest.properties"
        }
    }

    /**
     * The locally built image is used as-is; Testcontainers must not try to pull it.
     *
     * Readiness is gated on both the `Running server` log line *and* the Bolt/HTTP ports
     * actually listening, so `start()` does not return until jQAssistant is reachable.
     */
    val instance: GenericContainer<*> =
        GenericContainer(DockerImageName.parse(imageName)).apply {
            withExposedPorts(HTTP_PORT, BOLT_PORT)
            waitingFor(
                WaitAllStrategy()
                    .withStrategy(Wait.forLogMessage(".*Running server.*", 1))
                    .withStrategy(Wait.forListeningPort())
                    .withStartupTimeout(Duration.ofMinutes(2)),
            )
        }

    val host: String
        get() = instance.host

    /** Host port mapped to the Neo4j browser (container port 7474). */
    val httpPort: Int
        get() = instance.getMappedPort(HTTP_PORT)

    /** Host port mapped to the Bolt connector (container port 7687). */
    val boltPort: Int
        get() = instance.getMappedPort(BOLT_PORT)

    val boltUri: String
        get() = "bolt://$host:$boltPort"

    /**
     * Opens a Neo4j driver against the served store. The server runs without authentication
     * (`NO_AUTH`). Close the returned driver after use.
     */
    fun openDriver(): Driver = GraphDatabase.driver(boltUri, AuthTokens.none())
}
