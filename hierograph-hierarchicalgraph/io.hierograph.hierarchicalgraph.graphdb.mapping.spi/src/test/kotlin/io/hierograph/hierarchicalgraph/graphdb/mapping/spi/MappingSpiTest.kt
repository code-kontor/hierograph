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
package io.hierograph.hierarchicalgraph.graphdb.mapping.spi

import io.hierograph.hierarchicalgraph.core.model.HGNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MappingSpiTest {

    @Test
    fun `MappingProviderMetadata properties`() {
        val meta = MappingProviderMetadata(
            identifier = "test.provider",
            name = "Test Provider",
            description = "A test",
            categories = mapOf("lang" to "java")
        )
        assertThat(meta.identifier).isEqualTo("test.provider")
        assertThat(meta.name).isEqualTo("Test Provider")
        assertThat(meta.description).isEqualTo("A test")
        assertThat(meta.categories).containsEntry("lang", "java")
    }

    @Test
    fun `MappingProviderMetadata defaults`() {
        val meta = MappingProviderMetadata(identifier = "id", name = "n")
        assertThat(meta.description).isNull()
        assertThat(meta.categories).isEmpty()
    }

    @Test
    fun `DependencyDefinition properties`() {
        val dep = DependencyDefinition(
            idStart = 1L, idTarget = 2L, idRel = 3L,
            type = "DEPENDS_ON", weight = 5, attributesBitmap = 0b0011
        )
        assertThat(dep.idStart).isEqualTo(1L)
        assertThat(dep.idTarget).isEqualTo(2L)
        assertThat(dep.idRel).isEqualTo(3L)
        assertThat(dep.type).isEqualTo("DEPENDS_ON")
        assertThat(dep.weight).isEqualTo(5)
        assertThat(dep.attributesBitmap).isEqualTo(3)
    }

    @Test
    fun `DependencyDefinition defaults`() {
        val dep = DependencyDefinition(
            idStart = 1L, idTarget = 2L, idRel = 3L, type = "USES"
        )
        assertThat(dep.weight).isEqualTo(1)
        assertThat(dep.attributesBitmap).isEqualTo(0)
    }

    @Test
    fun `RootNode data class`() {
        val root = ToplevelNodeId(id = 42L, kind = "MODULE")
        assertThat(root.id).isEqualTo(42L)
        assertThat(root.kind).isEqualTo("MODULE")
    }

    @Test
    fun `ParentChildNode data class`() {
        val pc = ParentChildNodeId(parentId = 1L, childId = 2L, childKind = "CLASS")
        assertThat(pc.parentId).isEqualTo(1L)
        assertThat(pc.childId).isEqualTo(2L)
        assertThat(pc.childKind).isEqualTo("CLASS")
    }

    @Test
    fun `MappingProvider aggregates providers`() {
        val meta = MappingProviderMetadata(identifier = "test", name = "Test")
        val hierarchy = object : IHierarchyDefinitionProvider {
            override fun initialize() {}
            override fun dispose() {}
            override val toplevelNodeIds = listOf(ToplevelNodeId(1L, "MODULE"))
            override val parentChildNodeIds = emptyList<ParentChildNodeId>()
        }
        val deps = object : IDependencyDefinitionProvider {
            override fun initialize() {}
            override fun dispose() {}
            override val dependencies = emptyList<DependencyDefinition>()
        }

        val provider = MappingProvider(meta, hierarchy, deps)

        assertThat(provider.metadata).isSameAs(meta)
        assertThat(provider.hierarchyDefinitionProvider).isSameAs(hierarchy)
        assertThat(provider.dependencyDefinitionProvider).isSameAs(deps)
        assertThat(provider.hierarchyDefinitionProvider.toplevelNodeIds).hasSize(1)
    }
}
