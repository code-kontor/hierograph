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
 * Encodes a concrete `INodeSource` implementation as a string-keyed payload
 * and reconstructs an `INodeSource` on the read side.
 *
 * The read return type is the SPI base [INodeSource], not [S], so a codec can
 * legitimately write one impl and read back a plain copy of a different impl
 * (e.g. the graphdb codec writes `GraphDbNodeSource` and reads
 * `DefaultNodeSource`).
 *
 * Implementations are responsible for round-tripping the source's
 * `identifier`. The wire stores it as a `String`; codecs that need the
 * original numeric type should embed a type marker in their payload (see
 * `DefaultNodeSourceCodec` for the convention).
 */
interface NodeSourceCodec<S : INodeSource> {
    val typeId: String
    val sourceClass: Class<S>
    fun write(source: S): Map<String, String>
    fun read(identifier: String, payload: Map<String, String>): INodeSource
}

/** Mirror of [NodeSourceCodec] for `IDependencySource`. */
interface DepSourceCodec<S : IDependencySource> {
    val typeId: String
    val sourceClass: Class<S>
    fun write(source: S): Map<String, String>
    fun read(identifier: String, payload: Map<String, String>): IDependencySource
}
