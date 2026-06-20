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
package io.hierograph.itest.mcptools.dependencies

import io.hierograph.itest.fwk.AbstractMcpApplicationIntegrationTest
import io.hierograph.mcp.server.tools.dependencyanalysis.OutgoingDependenciesTool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Regression test for the class-level annotation bug: a class annotated with an
 * *internally-defined* annotation (a scanned `java.annotation` type) must be modeled as
 * `is_annotated_by` at type level and queryable as `annotated_by` at detail level — not
 * mis-classified as a generic `is_depends_on_other` dependency.
 *
 * The scanned fixture is Spring Framework, so `@Configuration`
 * (`org.springframework.context.annotation.Configuration`) is itself an internal annotation.
 * `ProxyCachingConfiguration` is annotated `@Configuration`; that class→annotation edge carries
 * no `EXTENDS`/`IMPLEMENTS`, so the annotation usage is the sole reason for the dependency —
 * making it a precise probe for the classifier.
 */
class ClassLevelAnnotatedByIT : AbstractMcpApplicationIntegrationTest() {

    @Autowired
    private lateinit var outgoingDependenciesTool: OutgoingDependenciesTool

    private val annotatedClassFqn = "org.springframework.cache.annotation.ProxyCachingConfiguration"
    private val annotationFqn = "org.springframework.context.annotation.Configuration"

    private fun annotatedClassId() =
        resolveNodeId("ProxyCachingConfiguration", annotatedClassFqn, "java.class")

    private fun annotationId() =
        resolveNodeId("Configuration", annotationFqn, "java.annotation")

    @Test
    fun `the type-level class to internal-annotation edge is tagged is_annotated_by`() {
        val response = outgoingDependenciesTool.outgoingDependencies(
            fromId = annotatedClassId(),
            toId = annotationId(),
            detailLevel = "type",
            relationship = null,
            limit = 100,
            cursor = null
        )

        @Suppress("UNCHECKED_CAST")
        val edges = response["edges"] as List<Map<String, Any?>>
        assertThat(edges).hasSize(1)

        @Suppress("UNCHECKED_CAST")
        val attributes = edges.single()["attributes"] as Map<String, Boolean>
        assertThat(attributes["is_annotated_by"]).isEqualTo(true)
        // The annotation usage is the only reason for the edge, so the residual bucket must be off.
        assertThat(attributes["is_depends_on_other"]).isEqualTo(false)

        @Suppress("UNCHECKED_CAST")
        val byAttribute = (response["summary"] as Map<String, Any?>)["by_attribute"] as Map<String, Int>
        assertThat(byAttribute["is_annotated_by"]).isEqualTo(1)
    }

    @Test
    fun `the detail-level annotated_by relationship resolves class-level annotations`() {
        val response = outgoingDependenciesTool.outgoingDependencies(
            fromId = annotatedClassId(),
            toId = annotationId(),
            detailLevel = "detail",
            relationship = "annotated_by",
            limit = 100,
            cursor = null
        )

        @Suppress("UNCHECKED_CAST")
        val edges = response["edges"] as List<Map<String, Any?>>
        assertThat(edges).hasSize(1)

        val edge = edges.single()
        assertThat(edge["relationship"]).isEqualTo("annotated_by")
        // For a class-level annotation the annotated element IS the type, so from == from_parent.
        assertThat(edge["from"]).isEqualTo(annotatedClassId())
        assertThat(edge["from_parent"]).isEqualTo(annotatedClassId())
        assertThat(edge["to"]).isEqualTo(annotationId())

        @Suppress("UNCHECKED_CAST")
        val byRelationship = (response["summary"] as Map<String, Any?>)["by_relationship"] as Map<String, Int>
        assertThat(byRelationship["annotated_by"]).isEqualTo(1)
    }
}
