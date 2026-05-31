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
    fun `DefaultMappingProviderMetadata properties`() {
        val meta = DefaultMappingProviderMetadata(
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
    fun `DefaultMappingProviderMetadata defaults`() {
        val meta = DefaultMappingProviderMetadata(identifier = "id", name = "n")
        assertThat(meta.description).isNull()
        assertThat(meta.categories).isEmpty()
    }

    @Test
    fun `DefaultDependencyDefinition properties`() {
        val dep = DefaultDependencyDefinition(
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
    fun `DefaultDependencyDefinition defaults`() {
        val dep = DefaultDependencyDefinition(
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
    fun `SearchResult data class`() {
        val sr = SearchResult(nodeId = 10L, name = "Foo", qualifiedName = "com.example.Foo", kind = "java.class")
        assertThat(sr.nodeId).isEqualTo(10L)
        assertThat(sr.name).isEqualTo("Foo")
        assertThat(sr.qualifiedName).isEqualTo("com.example.Foo")
        assertThat(sr.kind).isEqualTo("java.class")
    }

    @Test
    fun `DefaultMappingProvider aggregates providers`() {
        val meta = DefaultMappingProviderMetadata(identifier = "test", name = "Test")
        val hierarchy = object : IHierarchyDefinitionProvider {
            override fun getToplevelNodeIds() = listOf(ToplevelNodeId(1L, "MODULE"))
            override fun getParentChildNodeIds() = emptyList<ParentChildNodeId>()
        }
        val deps = object : IDependencyDefinitionProvider {
            override fun getDependencies() = emptyList<IDependencyDefinition>()
        }
        val nodeMeta = stubNodeMetadataProvider()

        val provider = DefaultMappingProvider(meta, hierarchy, deps, nodeMeta)

        assertThat(provider.metadata).isSameAs(meta)
        assertThat(provider.hierarchyDefinitionProvider).isSameAs(hierarchy)
        assertThat(provider.dependencyDefinitionProvider).isSameAs(deps)
        assertThat(provider.nodeMetadataProvider).isSameAs(nodeMeta)
        assertThat(provider.hierarchyDefinitionProvider.getToplevelNodeIds()).hasSize(1)
    }

    private fun stubNodeMetadataProvider() = object : INodeMetadataProvider {
        override fun getName(node: HGNode) = "name"
        override fun getQualifiedName(node: HGNode) = "fqn"
        override fun getKind(node: HGNode) = "Class"
        override fun getKindFromLabels(labels: List<String>) = "Class"
        override fun getKnownKinds() = listOf("Class")
        override fun getFindNodeCypherQuery(kind: String?, limit: Int) = ""
        override fun getNodeCountCypherQuery(scopeId: Long?) = ""
        override fun getDepthStatsCypherQuery(scopeId: Long?) = ""
        override fun getDependencyKindDistributionCypherQuery(scopeId: Long?) = ""
        override fun getScanMetadataCypherQuery() = ""
        override fun getScannerName() = "test"
    }
}
