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
package io.hierograph.itest

import io.hierograph.itest.support.AbstractSpringIntegrationTest
import io.hierograph.mcp.server.core.HierarchicalGraphService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Asserts that the hierarchical graph built from the served jQAssistant store has the expected
 * shape. The Spring context (and thus the graph) is created once and shared across all
 * integration-test classes — see [AbstractSpringIntegrationTest].
 */
class SmokeIT : AbstractSpringIntegrationTest() {

    @Autowired
    private lateinit var graphService: HierarchicalGraphService

    @Test
    fun `the hierarchical graph root has the expected number of children`() {
        val hierarchy = graphService.model.hierarchy
        val children = hierarchy.childrenOf(hierarchy.rootNode)
        assertThat(children).hasSize(22)
    }
}
