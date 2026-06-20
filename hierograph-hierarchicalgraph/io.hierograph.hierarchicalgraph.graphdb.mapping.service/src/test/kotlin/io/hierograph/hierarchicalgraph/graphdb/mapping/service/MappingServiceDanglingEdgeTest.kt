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
package io.hierograph.hierarchicalgraph.graphdb.mapping.service

import io.hierograph.boltclient.IBoltClient
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.DependencyDefinition
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinitionProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IHierarchyDefinitionProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.MappingProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.MappingProviderMetadata
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ParentChildNodeId
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.ToplevelNodeId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.neo4j.driver.EagerResult
import org.neo4j.driver.Result
import org.neo4j.driver.types.Node
import org.neo4j.driver.types.Relationship
import java.util.concurrent.Future
import java.util.function.Function

/**
 * Regression test for the dangling type-level edge bug: the containment hierarchy is authoritative, so
 * a dependency may only exist between nodes that are reachable from the root. An endpoint that was
 * created during construction (e.g. a test type pulled in via a package→type relationship) but never
 * attached under the root must not leave a dangling edge behind.
 *
 * See [DefaultMappingService] step 6 — edges whose source or target is absent from the hierarchy are
 * pruned.
 */
class MappingServiceDanglingEdgeTest {

    @Test
    fun `dependencies whose endpoints are not in the hierarchy are pruned`() {
        // module(10) -> package(20) -> type(30, included)
        // type(99) is created via a parent-child row whose parent package(98) is never linked to a
        // module, so the whole 98/99 subtree is orphaned (not reachable from the root) — exactly the
        // shape of an excluded test type that still participates in DEPENDS_ON edges.
        val hierarchyProvider = FakeHierarchyProvider(
            toplevel = listOf(ToplevelNodeId(10L, "java.module")),
            parentChild = listOf(
                ParentChildNodeId(10L, 20L, "java.package"),
                ParentChildNodeId(20L, 30L, "java.class"),
                // orphaned subtree — package(98) is never a child of any module
                ParentChildNodeId(98L, 99L, "java.class"),
            )
        )
        val dependencyProvider = FakeDependencyProvider(
            listOf(
                // kept: both endpoints reachable from the root
                DependencyDefinition(idStart = 30L, idTarget = 30L, idRel = 1L, type = "DEPENDS_ON"),
                // dropped: target 99 is an orphaned (excluded) node
                DependencyDefinition(idStart = 30L, idTarget = 99L, idRel = 2L, type = "DEPENDS_ON"),
                // dropped: source 99 is an orphaned (excluded) node
                DependencyDefinition(idStart = 99L, idTarget = 30L, idRel = 3L, type = "DEPENDS_ON"),
            )
        )
        val provider = MappingProvider(
            metadata = MappingProviderMetadata(identifier = "fake", name = "Fake"),
            hierarchyDefinitionProvider = hierarchyProvider,
            dependencyDefinitionProvider = dependencyProvider
        )

        val model = DefaultMappingService().convert(provider, ThrowingBoltClient)

        val includedType = model.coreGraph.lookupNode(30L)!!
        // The only surviving edge is the one between hierarchy-resident nodes.
        assertThat(includedType.outgoingCoreDependencies).hasSize(1)
        assertThat(includedType.incomingCoreDependencies).hasSize(1)
        // The orphaned node is pruned from the core graph entirely (coreGraph.nodes == hierarchy
        // tree), so it can neither be looked up nor participate in any edge.
        assertThat(model.coreGraph.lookupNode(99L)).isNull()
        assertThat(model.coreGraph.lookupNode(98L)).isNull()
        assertThat(model.coreGraph.nodes.map { it.identifier })
            .containsExactlyInAnyOrder(model.hierarchy.rootNode.identifier, 10L, 20L, 30L)
    }

    // ── test doubles ────────────────────────────────────────────────────

    private class FakeHierarchyProvider(
        private val toplevel: List<ToplevelNodeId>,
        private val parentChild: List<ParentChildNodeId>
    ) : IHierarchyDefinitionProvider {
        override fun initialize() {}
        override fun dispose() {}
        override val toplevelNodeIds: List<ToplevelNodeId> get() = toplevel
        override val parentChildNodeIds: List<ParentChildNodeId> get() = parentChild
    }

    private class FakeDependencyProvider(
        private val deps: List<DependencyDefinition>
    ) : IDependencyDefinitionProvider {
        override fun initialize() {}
        override fun dispose() {}
        override val dependencies: List<DependencyDefinition> get() = deps
    }

    /** The bolt client is only stored (for lazy property loading), never invoked during convert(). */
    private object ThrowingBoltClient : IBoltClient {
        override val name: String? get() = null
        override val description: String? get() = null
        override val uri: String get() = "bolt://test"
        override val isConnected: Boolean get() = false
        override fun connect() = throw UnsupportedOperationException()
        override fun disconnect() = throw UnsupportedOperationException()
        override fun getNode(nodeId: Long): Node = throw UnsupportedOperationException()
        override fun getRelationship(relationshipId: Long): Relationship = throw UnsupportedOperationException()
        override fun getNodeLabels(): List<String> = throw UnsupportedOperationException()
        override fun getPropertyKeys(): List<String> = throw UnsupportedOperationException()
        override fun getRelationshipTypes(): List<String> = throw UnsupportedOperationException()
        override fun syncExecCypherQuery(cypherQuery: String): EagerResult = throw UnsupportedOperationException()
        override fun syncExecCypherQuery(cypherQuery: String, params: Map<String, Any>): EagerResult =
            throw UnsupportedOperationException()
        override fun <T> asyncExecCypherQueryAndTransformResult(
            cypherQuery: String,
            transform: Function<Result, T>
        ): Future<T> = throw UnsupportedOperationException()
        override fun <T> asyncExecCypherQueryAndTransformResult(
            cypherQuery: String,
            params: Map<String, Any>,
            transform: Function<Result, T>
        ): Future<T> = throw UnsupportedOperationException()
    }
}
