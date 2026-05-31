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

// The payload key under which identifier type metadata is stored.
internal const val ID_TYPE_KEY = "_idType"

/**
 * Codec for `DefaultNodeSource`. Round-trips the source's `identifier` (Long,
 * Int, or String — anything else raises) and its `properties` map.
 */
object DefaultNodeSourceCodec : NodeSourceCodec<DefaultNodeSource> {
    override val typeId: String = "default-node"
    override val sourceClass: Class<DefaultNodeSource> = DefaultNodeSource::class.java

    override fun write(source: DefaultNodeSource): Map<String, String> = buildMap {
        put(ID_TYPE_KEY, identifierTypeKey(source.identifier))
        putAll(source.properties)
    }

    override fun read(identifier: String, payload: Map<String, String>): DefaultNodeSource {
        val idType = payload[ID_TYPE_KEY]
        val props = payload.filterKeys { it != ID_TYPE_KEY }.toMutableMap()
        return DefaultNodeSource(identifier = coerceIdentifier(identifier, idType), properties = props)
    }
}

/** Mirror of [DefaultNodeSourceCodec] for `DefaultDependencySource`. */
object DefaultDependencySourceCodec : DepSourceCodec<DefaultDependencySource> {
    override val typeId: String = "default-dep"
    override val sourceClass: Class<DefaultDependencySource> = DefaultDependencySource::class.java

    override fun write(source: DefaultDependencySource): Map<String, String> = buildMap {
        put(ID_TYPE_KEY, identifierTypeKey(source.identifier))
        putAll(source.properties)
    }

    override fun read(identifier: String, payload: Map<String, String>): DefaultDependencySource {
        val idType = payload[ID_TYPE_KEY]
        val props = payload.filterKeys { it != ID_TYPE_KEY }.toMutableMap()
        return DefaultDependencySource(identifier = coerceIdentifier(identifier, idType), properties = props)
    }
}

internal fun identifierTypeKey(id: Any): String = when (id) {
    is Long -> "long"
    is Int -> "int"
    is String -> "string"
    else -> throw UnsupportedOperationException(
        "Unsupported identifier type ${id::class.java.name}; register a custom codec to handle it."
    )
}

internal fun coerceIdentifier(idString: String, idType: String?): Any = when (idType) {
    "long" -> idString.toLong()
    "int" -> idString.toInt()
    "string" -> idString
    null -> throw IllegalArgumentException("Missing '$ID_TYPE_KEY' in payload; cannot coerce identifier '$idString'")
    else -> throw IllegalArgumentException("Unknown identifier type key '$idType' for identifier '$idString'")
}
