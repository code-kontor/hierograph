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

import io.hierograph.hierarchicalgraph.core.model.IDependencySource
import io.hierograph.hierarchicalgraph.core.model.INodeSource

/**
 * Holds the per-impl codecs the serializer dispatches to. Lookup by class
 * matches the exact class first, then walks superclasses; lookup by typeId is
 * exact.
 */
class CodecRegistry {

    private val nodeByType = mutableMapOf<String, NodeSourceCodec<INodeSource>>()
    private val nodeByClass = mutableMapOf<Class<*>, NodeSourceCodec<INodeSource>>()
    private val depByType = mutableMapOf<String, DepSourceCodec<IDependencySource>>()
    private val depByClass = mutableMapOf<Class<*>, DepSourceCodec<IDependencySource>>()

    @Suppress("UNCHECKED_CAST")
    fun register(codec: NodeSourceCodec<*>): CodecRegistry {
        val c = codec as NodeSourceCodec<INodeSource>
        nodeByType[c.typeId] = c
        nodeByClass[c.sourceClass] = c
        return this
    }

    @Suppress("UNCHECKED_CAST")
    fun register(codec: DepSourceCodec<*>): CodecRegistry {
        val c = codec as DepSourceCodec<IDependencySource>
        depByType[c.typeId] = c
        depByClass[c.sourceClass] = c
        return this
    }

    fun nodeCodecFor(source: INodeSource): NodeSourceCodec<INodeSource> =
        findByClass(source.javaClass, nodeByClass)
            ?: error("No NodeSourceCodec registered for ${source.javaClass.name}")

    fun nodeCodecFor(typeId: String): NodeSourceCodec<INodeSource> =
        nodeByType[typeId]
            ?: error("No NodeSourceCodec registered for typeId '$typeId'")

    fun depCodecFor(source: IDependencySource): DepSourceCodec<IDependencySource> =
        findByClass(source.javaClass, depByClass)
            ?: error("No DepSourceCodec registered for ${source.javaClass.name}")

    fun depCodecFor(typeId: String): DepSourceCodec<IDependencySource> =
        depByType[typeId]
            ?: error("No DepSourceCodec registered for typeId '$typeId'")

    private fun <V> findByClass(cls: Class<*>, table: Map<Class<*>, V>): V? {
        var c: Class<*>? = cls
        while (c != null) {
            table[c]?.let { return it }
            c = c.superclass
        }
        return null
    }

    companion object {
        /** Registry preloaded with codecs for the default and graphdb `INodeSource` / `IDependencySource` impls. */
        fun defaults(): CodecRegistry = CodecRegistry()
            .register(DefaultNodeSourceCodec)
            .register(DefaultDependencySourceCodec)
            .register(GraphDbRootNodeSourceCodec)
            .register(GraphDbNodeSourceCodec)
            .register(GraphDbDependencySourceCodec)
    }
}
