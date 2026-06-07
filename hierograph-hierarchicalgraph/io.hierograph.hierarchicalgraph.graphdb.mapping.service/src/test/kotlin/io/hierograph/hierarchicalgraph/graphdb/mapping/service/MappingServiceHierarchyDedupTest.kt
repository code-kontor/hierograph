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
 * Regression test for the `graph_overview` duplicate-hierarchy bug: a top-level id returned more than
 * once by the hierarchy provider must be added under the root exactly once. The graph-construction
 * invariant ("an id is never added to the hierarchy twice") is enforced in [DefaultMappingService]
 * regardless of whether the provider deduplicates its own results.
 */
class MappingServiceHierarchyDedupTest {

    @Test
    fun `a top-level id returned more than once is added under the root exactly once`() {
        val hierarchyProvider = FakeHierarchyProvider(
            toplevel = listOf(
                ToplevelNodeId(10L, "java.module"),
                ToplevelNodeId(10L, "java.module"), // duplicate (e.g. a Main + Test artifact row)
                ToplevelNodeId(20L, "java.module"),
            ),
            parentChild = emptyList()
        )
        val provider = MappingProvider(
            metadata = MappingProviderMetadata(identifier = "fake", name = "Fake"),
            hierarchyDefinitionProvider = hierarchyProvider,
            dependencyDefinitionProvider = FakeDependencyProvider()
        )

        val model = DefaultMappingService().convert(provider, ThrowingBoltClient)

        val childIds = model.hierarchy
            .childrenOf(model.hierarchy.rootNode)
            .map { it.identifier }
        assertThat(childIds).containsExactly(10L, 20L)
        assertThat(childIds).doesNotHaveDuplicates()
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

    private class FakeDependencyProvider : IDependencyDefinitionProvider {
        override fun initialize() {}
        override fun dispose() {}
        override val dependencies: List<DependencyDefinition> = emptyList()
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
