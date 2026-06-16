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
package io.hierograph.itest.hierarchicalgraph

import io.hierograph.itest.fwk.AbstractHierarchicalGraphIntegrationTest
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantNodeMetadataProvider
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

/**
 * Asserts that the root children (the top-level modules) of the hierarchical graph built from the
 * served jQAssistant store have the expected names, fully qualified names, and kinds. The graph is
 * created once and shared across all hierarchical-graph integration tests —
 * see [io.hierograph.itest.fwk.AbstractHierarchicalGraphIntegrationTest].
 */
class HierarchicalGraphRootChildrenIT : AbstractHierarchicalGraphIntegrationTest() {

    @Test
    fun `the hierarchical graph root children have the expected names`(): Unit = withHierarchyScope {
        val children = hierarchy.rootNode.children

        val names = children.map { JQAssistantNodeMetadataProvider.getName(it) }
        Assertions.assertThat(names).containsExactlyInAnyOrder(
            "spring-r2dbc-7.0.8.jar",
            "spring-messaging-7.0.8.jar",
            "spring-websocket-7.0.8.jar",
            "spring-aop-7.0.8.jar",
            "spring-test-7.0.8.jar",
            "spring-orm-7.0.8.jar",
            "spring-context-7.0.8.jar",
            "spring-core-7.0.8.jar",
            "spring-webflux-7.0.8.jar",
            "spring-aspects-7.0.8.jar",
            "spring-jms-7.0.8.jar",
            "spring-beans-7.0.8.jar",
            "spring-context-support-7.0.8.jar",
            "spring-webmvc-7.0.8.jar",
            "spring-context-indexer-7.0.8.jar",
            "spring-oxm-7.0.8.jar",
            "spring-tx-7.0.8.jar",
            "spring-jdbc-7.0.8.jar",
            "spring-instrument-7.0.8.jar",
            "spring-web-7.0.8.jar",
            "spring-expression-7.0.8.jar",
            "External Types",
        )
    }

    @Test
    fun `the hierarchical graph root children have the expected fully qualified names`(): Unit = withHierarchyScope {
        val children = hierarchy.rootNode.children

        val fullyQualifiedNames = children.map { JQAssistantNodeMetadataProvider.getQualifiedName(it) }
        Assertions.assertThat(fullyQualifiedNames).containsExactlyInAnyOrder(
            "spring-r2dbc-7.0.8.jar",
            "spring-messaging-7.0.8.jar",
            "spring-websocket-7.0.8.jar",
            "spring-aop-7.0.8.jar",
            "spring-test-7.0.8.jar",
            "spring-orm-7.0.8.jar",
            "spring-context-7.0.8.jar",
            "spring-core-7.0.8.jar",
            "spring-webflux-7.0.8.jar",
            "spring-aspects-7.0.8.jar",
            "spring-jms-7.0.8.jar",
            "spring-beans-7.0.8.jar",
            "spring-context-support-7.0.8.jar",
            "spring-webmvc-7.0.8.jar",
            "spring-context-indexer-7.0.8.jar",
            "spring-oxm-7.0.8.jar",
            "spring-tx-7.0.8.jar",
            "spring-jdbc-7.0.8.jar",
            "spring-instrument-7.0.8.jar",
            "spring-web-7.0.8.jar",
            "spring-expression-7.0.8.jar",
            "External Types",
        )
    }

    @Test
    fun `the hierarchical graph root children have the expected kinds`(): Unit = withHierarchyScope {
        val children = hierarchy.rootNode.children

        val kinds = children.map { JQAssistantNodeMetadataProvider.getKind(it) }
        Assertions.assertThat(kinds)
            .hasSize(22)
            .containsOnly("java.module")
    }
}