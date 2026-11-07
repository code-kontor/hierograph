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
package io.hierograph.graphstore.hgmodel

import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.model.ArtifactInfo
import io.hierograph.graphstore.model.ClassFacts
import io.hierograph.graphstore.model.Origin
import io.hierograph.hierarchicalgraph.core.model.NodeHandle
import io.hierograph.javaspec.JavaKinds
import io.hierograph.javaspec.JavaNodeKind
import io.hierograph.hierarchicalgraph.core.model.NodeMetadata
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The adapter check: the existing (jQAssistant) node-metadata provider must resolve name / qualified
 * name / kind from a GraphStore-backed HGModel — i.e. off the `INodeSource` abstraction, with no Neo4j.
 */
class GraphStoreMetadataTest {

    @Test
    fun `NodeMetadata resolves name, fqn and kind from the bridged model`() {
        val store = GraphStore()
        store.registerArtifact(ArtifactInfo("app", "app", "app", Origin.MODULE_MAIN))
        store.applyClass("app", ClassFacts("com.foo.Service", JavaKinds.CLASS, 0, emptyList(), emptyList(), "h1"))

        val model = GraphStoreToHGModel.build(store)
        // Nodes are identified by the content-addressed handle of their artifact-scoped GraphStore key.
        val node = model.lookupNode(NodeHandle.of(store.typeKey("app", "com.foo.Service")))!!

        assertThat(NodeMetadata.getName(node)).isEqualTo("Service")
        assertThat(NodeMetadata.getQualifiedName(node)).isEqualTo("com.foo.Service")
        assertThat(NodeMetadata.getKind(node)).isEqualTo(JavaNodeKind.CLASS.value)

        // a package node resolves too (package keys are already artifact-scoped)
        val pkg = model.lookupNode(NodeHandle.of("pkg:app:com.foo"))!!
        assertThat(NodeMetadata.getName(pkg)).isEqualTo("foo")
        assertThat(NodeMetadata.getQualifiedName(pkg)).isEqualTo("com.foo")
    }
}
