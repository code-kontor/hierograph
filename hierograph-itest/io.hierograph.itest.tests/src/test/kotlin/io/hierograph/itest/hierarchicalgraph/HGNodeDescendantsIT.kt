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
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.javaspec.JavaNodeKind
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantNodeMetadataProvider
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Retrieves the full descendant subtree of the `spring-core` module from the hierarchical graph
 * built from the served jQAssistant store and asserts its shape (packages, types and members). The
 * graph is created once and shared across all hierarchical-graph integration tests —
 * see [io.hierograph.itest.fwk.AbstractHierarchicalGraphIntegrationTest].
 */
class HGNodeDescendantsIT : AbstractHierarchicalGraphIntegrationTest() {

    @Test
    fun `the spring-core module exposes its full descendant subtree`(): Unit = withHierarchyScope {
        val springCore = hierarchy.rootNode.children.single {
            JQAssistantNodeMetadataProvider.getName(it) == "spring-core-7.0.8.jar"
        }

        val descendants = springCore.descendants()
        val fqns = descendants.map { JQAssistantNodeMetadataProvider.getQualifiedName(it) }.toSet()

        // the module's single top-level package
        Assertions.assertThat(springCore.children.map { JQAssistantNodeMetadataProvider.getName(it) })
            .containsExactly("org")

        // representative packages and types of the spring-core jar are reachable as descendants
        Assertions.assertThat(fqns).contains(
            "org.springframework.core",
            "org.springframework.core.io",
            "org.springframework.util",
            "org.springframework.core.io.Resource",
            "org.springframework.util.StringUtils",
        )

        // the subtree has the exact expected size ...
        Assertions.assertThat(descendants).hasSize(14_160)

        // ... broken down per Java node kind (packages, the five type kinds, and both members)
        val countsByKind = descendants.groupingBy { JQAssistantNodeMetadataProvider.getKind(it) }.eachCount()
        Assertions.assertThat(countsByKind).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                JavaKinds.PACKAGE.value to 62,
                JavaKinds.CLASS.value to 941,
                JavaKinds.INTERFACE.value to 215,
                JavaKinds.ENUM.value to 29,
                JavaKinds.ANNOTATION.value to 13,
                JavaKinds.RECORD.value to 15,
                JavaKinds.METHOD.value to 9_477,
                JavaKinds.FIELD.value to 3_408,
            )
        )
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "java.package,      62",
        "java.class,       941",
        "java.interface,   215",
        "java.enum,         29",
        "java.annotation,   13",
        "java.record,       15",
        "java.method,     9477",
        "java.field,      3408",
    )
    fun `descendants can be filtered to a single kind`(kind: String, expectedCount: Int): Unit = withHierarchyScope {
        val springCore = hierarchy.rootNode.children.single {
            JQAssistantNodeMetadataProvider.getName(it) == "spring-core-7.0.8.jar"
        }

        val filtered = springCore.descendants(JavaNodeKind.fromValue(kind)!!)
        val allDescendants = springCore.descendants()

        Assertions.assertThat(filtered)
            .hasSize(expectedCount)
            .allMatch { JQAssistantNodeMetadataProvider.getKind(it) == kind }
            .isEqualTo(allDescendants.filter { JQAssistantNodeMetadataProvider.getKind(it) == kind })
    }
}
