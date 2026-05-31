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
package io.hierograph.hierarchicalgraph.serialization.internal

import io.hierograph.hierarchicalgraph.core.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.IDependencySource
import io.hierograph.hierarchicalgraph.core.model.INodeSource
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbDependencySource
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbNodeSource
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbRootNodeSource

// v1: identifier-only — we do NOT force-load `labels` / `properties` from
// Neo4j on write, and we do NOT preserve any source-specific state. Each
// graphdb source round-trips as the matching plain `Default*Source`, so the
// deserialized graph carries no live Bolt binding and no lazy-loaded
// metadata. If you need labels/properties in the snapshot, extend these
// codecs and bump GraphSnapshot.SCHEMA_VERSION.

internal object GraphDbRootNodeSourceCodec : NodeSourceCodec<GraphDbRootNodeSource> {
    override val typeId: String = "graphdb-root"
    override val sourceClass: Class<GraphDbRootNodeSource> = GraphDbRootNodeSource::class.java

    override fun write(source: GraphDbRootNodeSource): Map<String, String> =
        mapOf(ID_TYPE_KEY to identifierTypeKey(source.identifier))

    override fun read(identifier: String, payload: Map<String, String>): INodeSource =
        DefaultNodeSource(identifier = coerceIdentifier(identifier, payload[ID_TYPE_KEY]))
}

internal object GraphDbNodeSourceCodec : NodeSourceCodec<GraphDbNodeSource> {
    override val typeId: String = "graphdb-node"
    override val sourceClass: Class<GraphDbNodeSource> = GraphDbNodeSource::class.java

    override fun write(source: GraphDbNodeSource): Map<String, String> =
        mapOf(ID_TYPE_KEY to identifierTypeKey(source.identifier))

    override fun read(identifier: String, payload: Map<String, String>): INodeSource =
        DefaultNodeSource(identifier = coerceIdentifier(identifier, payload[ID_TYPE_KEY]))
}

internal object GraphDbDependencySourceCodec : DepSourceCodec<GraphDbDependencySource> {
    override val typeId: String = "graphdb-dep"
    override val sourceClass: Class<GraphDbDependencySource> = GraphDbDependencySource::class.java

    override fun write(source: GraphDbDependencySource): Map<String, String> =
        mapOf(ID_TYPE_KEY to identifierTypeKey(source.identifier))

    override fun read(identifier: String, payload: Map<String, String>): IDependencySource =
        DefaultDependencySource(identifier = coerceIdentifier(identifier, payload[ID_TYPE_KEY]))
}
