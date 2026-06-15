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

import io.hierograph.hierarchicalgraph.core.model.HGModel
import io.hierograph.mcp.server.core.HierarchicalGraphService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Base for integration tests that operate on the hierarchical graph. It boots the
 * [HierarchicalGraphService], which connects to the container's jQAssistant store and builds the
 * graph once (in its `@PostConstruct`). The built [model] is exposed to subclasses.
 *
 * Only the graph service is loaded into the context; the shared, identical configuration across
 * subclasses means the graph is built exactly once for the whole test run.
 */
@SpringBootTest(classes = [HierarchicalGraphService::class])
abstract class AbstractHierarchicalGraphIntegrationTest : AbstractSpringIntegrationTest() {

    @Autowired
    protected lateinit var graphService: HierarchicalGraphService

    /** The hierarchical graph model built from the served jQAssistant store. */
    protected val model: HGModel
        get() = graphService.model

    companion object {

        @JvmStatic
        @DynamicPropertySource
        fun graphProperties(registry: DynamicPropertyRegistry) {
            registry.add("hierograph.graph.dumpFile") { "target/hierarchical-graph.txt" }
        }
    }
}
