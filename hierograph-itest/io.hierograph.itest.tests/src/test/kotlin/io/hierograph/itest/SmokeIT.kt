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

import io.hierograph.itest.support.AbstractHierarchicalGraphIntegrationTest
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantNodeMetadataProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Asserts that the hierarchical graph built from the served jQAssistant store has the expected
 * shape. The graph is created once and shared across all hierarchical-graph integration tests —
 * see [AbstractHierarchicalGraphIntegrationTest].
 */
class SmokeIT : AbstractHierarchicalGraphIntegrationTest() {

    @Test
    fun `the hierarchical graph root has the expected number of children`() {
        val hierarchy = model.hierarchy
        val children = hierarchy.childrenOf(hierarchy.rootNode)
        assertThat(children).hasSize(22)

        children.forEach { println(JQAssistantNodeMetadataProvider.getQualifiedName(it)) }
    }
}
