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
package io.hierograph.hierarchicalgraph.serialization

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.hierograph.hierarchicalgraph.core.model.HGModel
import io.hierograph.hierarchicalgraph.serialization.internal.CodecRegistry
import io.hierograph.hierarchicalgraph.serialization.internal.GraphReader
import io.hierograph.hierarchicalgraph.serialization.internal.GraphSnapshot
import io.hierograph.hierarchicalgraph.serialization.internal.GraphWriter
import java.io.InputStream
import java.io.OutputStream

/**
 * JSON serializer for [HGModel] instances. See the design at
 * `docs/specifications/hierarchicalgraph-specifications/hierarchicalgraph-serialization-spec.md`.
 *
 * Out of the box, `DefaultNodeSource` and `DefaultDependencySource` are
 * supported. Graphs backed by other `INodeSource` / `IDependencySource` impls
 * will fail fast on [write].
 */
object HGGraphJson {

    private val compactMapper: ObjectMapper = newMapper(pretty = false)
    private val prettyMapper: ObjectMapper = newMapper(pretty = true)

    private val writer = GraphWriter(CodecRegistry.defaults())
    private val reader = GraphReader(CodecRegistry.defaults())

    /** Write [model] to a JSON string. Set [prettyPrint] for human-readable output. */
    fun write(model: HGModel, prettyPrint: Boolean = false): String =
        mapper(prettyPrint).writeValueAsString(writer.write(model))

    /** Write [model] to [sink] as JSON. The stream is left open. */
    fun write(model: HGModel, sink: OutputStream, prettyPrint: Boolean = false) {
        mapper(prettyPrint).writeValue(sink, writer.write(model))
    }

    /** Read a JSON string previously produced by [write] back into an [HGModel]. */
    fun read(json: String): HGModel =
        reader.read(compactMapper.readValue(json, GraphSnapshot::class.java))

    /** Read a JSON document from [source] back into an [HGModel]. */
    fun read(source: InputStream): HGModel =
        reader.read(compactMapper.readValue(source, GraphSnapshot::class.java))

    private fun mapper(pretty: Boolean): ObjectMapper =
        if (pretty) prettyMapper else compactMapper

    private fun newMapper(pretty: Boolean): ObjectMapper = jacksonObjectMapper()
        .configure(SerializationFeature.INDENT_OUTPUT, pretty)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}
