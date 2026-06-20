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
import io.hierograph.mcp.server.tools.dependencyanalysis.IncomingDependenciesTool
import io.hierograph.mcp.server.tools.dependencyanalysis.OutgoingDependenciesTool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Regression test for the *external*-annotation classification bug
 * (`BUG-hierograph-external-annotations-not-classified.md`): a class annotated with a
 * **library-defined** annotation (an `external.type` stub, lifted onto a canonical `:Virtual:Type`
 * by `hierograph:VirtualExternalAnnotatedBy`) must be modeled as `is_annotated_by` at type level and
 * queryable as `annotated_by` at detail level — and the external annotation node must carry the
 * reverse (incoming) index — not mis-classified as a generic `is_depends_on_other` dependency.
 *
 * This is the external counterpart of [ClassLevelAnnotatedByIT] (which covers an *internal*
 * annotation). The scanned fixture is Spring Framework; `AnnotationBeanConfigurerAspect` is annotated
 * with the external `@org.aspectj.lang.annotation.Aspect` (AspectJ is not part of the scanned
 * classpath, so the annotation type is an `external.type`). That class→annotation edge carries no
 * `EXTENDS`/`IMPLEMENTS`, so the annotation usage is the sole reason for the dependency — a precise
 * probe for the classifier.
 */
class ExternalClassLevelAnnotatedByIT : AbstractMcpApplicationIntegrationTest() {

    @Autowired
    private lateinit var outgoingDependenciesTool: OutgoingDependenciesTool

    @Autowired
    private lateinit var incomingDependenciesTool: IncomingDependenciesTool

    private val annotatedClassFqn =
        "org.springframework.beans.factory.aspectj.AnnotationBeanConfigurerAspect"
    private val annotationFqn = "org.aspectj.lang.annotation.Aspect"

    private fun annotatedClassId() =
        resolveNodeId("AnnotationBeanConfigurerAspect", annotatedClassFqn, "java.class")

    /**
     * Resolves the external annotation node. `find_node`'s kind_filter only accepts the `java.*`
     * kinds, not `external.type`, so we query by fully-qualified name and pin the single match by
     * fqn + `external.type` kind (the virtual canonical node; the raw stub is not in the hierarchy).
     */
    private fun annotationId(): Long {
        val response = findNodeTool.findNode(annotationFqn, null)

        @Suppress("UNCHECKED_CAST")
        val results = response["results"] as List<Map<String, Any?>>
        val match = results.single {
            it["qualified_name"] == annotationFqn && it["kind"] == "external.type"
        }
        return (match["id"] as Number).toLong()
    }

    @Test
    fun `the type-level class to external-annotation edge is tagged is_annotated_by`() {
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
    fun `the detail-level annotated_by relationship resolves an external class-level annotation`() {
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

    @Test
    fun `the external annotation carries an incoming index of its annotated classes`() {
        // "Who is annotated by @Aspect?" — the external annotation must be queryable as the source
        // of incoming_dependencies, returning the annotated class with is_annotated_by set.
        val response = incomingDependenciesTool.incomingDependencies(
            fromId = annotationId(),
            toId = annotatedClassId(),
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
    }
}
