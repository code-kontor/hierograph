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
package io.hierograph.itest.support

import io.hierograph.mcp.server.core.HierarchicalGraphService
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Base class for Spring-based integration tests. The Spring [SpringBootTest] configuration and the
 * [DynamicPropertySource] live here so that every subclass shares an *identical* context
 * configuration. Spring's test context cache therefore creates the [ApplicationContext] (and runs
 * the graph-building [HierarchicalGraphService]) only once for the whole test run, reusing it
 * across all integration-test classes.
 *
 * Only [HierarchicalGraphService] is loaded into the context; point `classes` at
 * [io.hierograph.mcp.server.McpApplication] to bring up the full application instead.
 */
@SpringBootTest(classes = [HierarchicalGraphService::class])
abstract class AbstractSpringIntegrationTest : AbstractIntegrationTest() {

    companion object {

        @JvmStatic
        @DynamicPropertySource
        fun springProperties(registry: DynamicPropertyRegistry) {
            // Ensure the container is up before the Spring context reads the property
            // (start() is idempotent; the shared lifecycle is owned by HierographImageExtension).
            HierographImageContainer.instance.start()
            registry.add("hierograph.bolt.uri") { HierographImageContainer.boltUri }
            registry.add("hierograph.graph.dumpFile") { "target/hierarchical-graph.txt" }
        }
    }
}
