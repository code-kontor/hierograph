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
package io.hierograph.graphstore.parquet

import dev.hardwood.OutputFile
import dev.hardwood.metadata.CompressionCodec
import dev.hardwood.schema.FileSchema
import dev.hardwood.writer.ParquetFileWriter
import dev.hardwood.writer.StructBuilder
import dev.hardwood.writer.WriterConfig
import io.hierograph.graphstore.model.ClassFacts
import io.hierograph.graphstore.model.DetailRef
import java.nio.file.Path

/**
 * Writes the GraphStore Parquet files (the [ParquetSchema] tables) from [ClassFacts], the same unit
 * the scanner and the reader exchange. This is the write counterpart of [ParquetReader]: a set
 * of classes written here loads back into an equivalent [io.hierograph.graphstore.GraphStore].
 *
 * Tables are ZSTD-compressed by default (the spec's choice; [ParquetSchema]'s dictionary encoding
 * absorbs the repeated FQNs). Pass [CompressionCodec.UNCOMPRESSED] to avoid needing a codec on the
 * classpath (e.g. in tests).
 */
object ParquetWriter {

    /** One scanned class together with the key of the artifact that owns it. */
    data class ArtifactClass(val artifactKey: String, val facts: ClassFacts)

    fun writeClassFacts(dir: Path, classes: List<ArtifactClass>, codec: CompressionCodec = CompressionCodec.ZSTD) {
        writeTable(dir.resolve(ParquetSchema.TYPES_FILE), ParquetSchema.typesSchema(), codec, classes) { sb, c ->
            sb.setString(ParquetSchema.Types.ARTIFACT, c.artifactKey)
            sb.setString(ParquetSchema.Types.FQN, c.facts.fqn)
            sb.setString(ParquetSchema.Types.KIND, c.facts.kind.name)
            sb.setNullable(ParquetSchema.Types.SOURCE_FILE, c.facts.sourceFile)
            sb.setNullable(ParquetSchema.Types.CLASS_HASH, c.facts.classHash.ifEmpty { null })
        }

        val members = classes.flatMap { c -> c.facts.members.map { c.facts.fqn to it } }
        writeTable(dir.resolve(ParquetSchema.MEMBERS_FILE), ParquetSchema.membersSchema(), codec, members) { sb, (owner, m) ->
            sb.setString(ParquetSchema.Members.OWNER_FQN, owner)
            sb.setString(ParquetSchema.Members.MEMBER_KEY, m.key)
            sb.setString(ParquetSchema.Members.KIND, m.kind.name)
            sb.setString(ParquetSchema.Members.NAME, m.name)
            sb.setString(ParquetSchema.Members.SIGNATURE, m.signature)
            sb.setNullableInt(ParquetSchema.Members.MODIFIERS, m.modifiers.takeIf { it != 0 })
            sb.setNullableInt(ParquetSchema.Members.LINE_FROM, m.lineFrom)
            sb.setNullableInt(ParquetSchema.Members.LINE_TO, m.lineTo)
        }

        // One row per parameter, keyed by the owning member; sorted so a scoped read skips row groups.
        val parameters = classes.flatMap { c -> c.facts.members }
            .flatMap { m -> m.parameters.map { m.key to it } }
            .sortedWith(compareBy({ it.first }, { it.second.index }))
        writeTable(dir.resolve(ParquetSchema.PARAMETERS_FILE), ParquetSchema.parametersSchema(), codec, parameters) { sb, (methodKey, p) ->
            sb.setString(ParquetSchema.Parameters.METHOD_KEY, methodKey)
            sb.setInt(ParquetSchema.Parameters.INDEX, p.index)
            sb.setString(ParquetSchema.Parameters.NAME, p.name)
            sb.setString(ParquetSchema.Parameters.TYPE_FQN, p.typeFqn)
        }

        val refs = classes.flatMap { c -> c.facts.refs.map { c.facts.fqn to it } }
        writeTable(dir.resolve(ParquetSchema.REFS_FILE), ParquetSchema.refsSchema(), codec, refs) { sb, (fromType, r) ->
            sb.setString(ParquetSchema.Refs.FROM_TYPE, fromType)
            sb.setString(ParquetSchema.Refs.TO_TYPE, r.toKey)
            sb.setString(ParquetSchema.Refs.KIND, r.kind.name)
            sb.setNullableInt(ParquetSchema.Refs.COUNT, r.count)
        }
    }

    /**
     * Writes member-level ("detail") refs to the `detail_refs` table, sorted by `from_type` (then
     * `from_key`) so a scoped read via [ParquetReader.readDetailRefs] skips row groups. Defaults to
     * uncompressed (callers that want ZSTD pass it explicitly).
     */
    fun writeDetailRefs(dir: Path, refs: List<DetailRef>, codec: CompressionCodec = CompressionCodec.UNCOMPRESSED) {
        val cols = ParquetSchema.DetailRefs
        val sorted = refs.sortedWith(compareBy({ it.fromType }, { it.fromKey }, { it.toType }, { it.toKey }, { it.relationship }))
        writeTable(dir.resolve(ParquetSchema.DETAIL_REFS_FILE), ParquetSchema.detailRefsSchema(), codec, sorted) { sb, r ->
            sb.setString(cols.FROM_KEY, r.fromKey)
            sb.setString(cols.FROM_TYPE, r.fromType)
            sb.setString(cols.TO_KEY, r.toKey)
            sb.setString(cols.TO_TYPE, r.toType)
            sb.setString(cols.RELATIONSHIP, r.relationship)
            sb.setNullableInt(cols.LINE, r.line)
        }
    }

    private fun <T> writeTable(path: Path, schema: FileSchema, codec: CompressionCodec, rows: List<T>, set: (StructBuilder, T) -> Unit) {
        val config = WriterConfig.builder().codec(codec).build()
        ParquetFileWriter.create(OutputFile.of(path), schema, config).use { writer ->
            val rowWriter = writer.rowWriter()
            for (row in rows) {
                rowWriter.writeRow { sb -> set(sb, row) }
            }
            writer.endRowGroup()
        }
    }

    private fun StructBuilder.setNullable(name: String, value: String?) {
        if (value == null) setNull(name) else setString(name, value)
    }

    private fun StructBuilder.setNullableInt(name: String, value: Int?) {
        if (value == null) setNull(name) else setInt(name, value)
    }
}
