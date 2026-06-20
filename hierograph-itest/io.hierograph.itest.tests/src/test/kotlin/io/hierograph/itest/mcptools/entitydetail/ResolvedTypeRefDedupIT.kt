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
package io.hierograph.itest.mcptools.entitydetail

import io.hierograph.itest.fwk.AbstractMcpApplicationIntegrationTest
import io.hierograph.mcp.server.tools.detail.TypeDetailsTool
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Regression test for raw+resolved duplication in `type_details`. When a type reference is resolved,
 * the scanner's raw stub edge and a parallel canonical edge tagged `resolved = true` both reach the
 * same type, so it would otherwise appear twice. This covers both flavours of resolution:
 *  - hierograph's virtual-external lift (stub → `:Virtual` canonical node), and
 *  - jQAssistant's own java-classpath:Resolve (stub → real, parsed type) — neither node is `:Virtual`.
 * The response must collapse each to a single entry, preferring the resolved (canonical) one.
 */
class ResolvedTypeRefDedupIT : AbstractMcpApplicationIntegrationTest() {

    @Autowired
    private lateinit var typeDetailsTool: TypeDetailsTool

    @Suppress("UNCHECKED_CAST")
    private fun refsOf(list: Any?): List<Map<String, Any?>> = list as List<Map<String, Any?>>

    private fun fqnsOf(list: Any?): List<String> = refsOf(list).map { it["qualified_name"] as String }

    @Test
    fun `external interfaces are listed once, not as stub plus virtual`() {
        // DelegatingConnectionFactory implements the external io.r2dbc.spi interfaces.
        val id = resolveNodeId(
            "DelegatingConnectionFactory",
            "org.springframework.r2dbc.connection.DelegatingConnectionFactory",
            "java.class"
        )
        val interfaceFqns = fqnsOf(typeDetailsTool.typeDetails(id)["interfaces"])

        assertThat(interfaceFqns)
            .contains("io.r2dbc.spi.ConnectionFactory", "io.r2dbc.spi.Wrapped")
        assertThat(interfaceFqns).doesNotHaveDuplicates()
    }

    @Test
    fun `an external class-level annotation is listed once, not as stub plus virtual`() {
        // AnnotationBeanConfigurerAspect is annotated with the external @org.aspectj.lang.annotation.Aspect.
        val id = resolveNodeId(
            "AnnotationBeanConfigurerAspect",
            "org.springframework.beans.factory.aspectj.AnnotationBeanConfigurerAspect",
            "java.class"
        )

        @Suppress("UNCHECKED_CAST")
        val annotations = typeDetailsTool.typeDetails(id)["annotations"] as List<Map<String, Any?>>
        val annotationFqns = annotations.map { (it["type"] as Map<String, Any?>)["qualified_name"] as String }

        assertThat(annotationFqns).filteredOn { it == "org.aspectj.lang.annotation.Aspect" }.hasSize(1)
        assertThat(annotationFqns).doesNotHaveDuplicates()
    }

    @Test
    fun `an internally-resolved interface (stub plus real) is listed once as the real type`() {
        // DefaultSimpUserRegistry implements SimpUserRegistry, which jQAssistant scanned as a stub
        // and resolved to the real parsed type — both nodes are non-:Virtual, so only the resolved
        // edge marker distinguishes them. The dedup must keep the single resolved (real) entry.
        val id = resolveNodeId(
            "DefaultSimpUserRegistry",
            "org.springframework.web.socket.messaging.DefaultSimpUserRegistry",
            "java.class"
        )
        // The real, parsed interface node (the resolved edge's target). The bytecode-less stub
        // carries no :Interface label, so it is not a java.interface node and find_node returns
        // exactly the real one.
        val realInterfaceId = resolveNodeId(
            "SimpUserRegistry",
            "org.springframework.messaging.simp.user.SimpUserRegistry",
            "java.interface"
        )

        val interfaces = refsOf(typeDetailsTool.typeDetails(id)["interfaces"])
        val simpRegistry = interfaces.filter {
            it["qualified_name"] == "org.springframework.messaging.simp.user.SimpUserRegistry"
        }

        assertThat(simpRegistry).hasSize(1)
        // Kept entry must be the resolved real interface node, not the raw stub.
        assertThat((simpRegistry.single()["id"] as Number).toLong()).isEqualTo(realInterfaceId)
        assertThat(fqnsOf(interfaces)).doesNotHaveDuplicates()
    }
}
