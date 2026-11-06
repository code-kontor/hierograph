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
import java.nio.file.Path

/**
 * Writes a GraphStore Parquet snapshot (the [SnapshotSchema] tables) from [ClassFacts], the same unit
 * the scanner and the loader exchange. This is the write counterpart of [ParquetSnapshotLoader]: a set
 * of classes written here loads back into an equivalent [io.hierograph.graphstore.GraphStore].
 *
 * Snapshots are ZSTD-compressed by default (the spec's choice; [SnapshotSchema]'s dictionary encoding
 * absorbs the repeated FQNs). Pass [CompressionCodec.UNCOMPRESSED] to avoid needing a codec on the
 * classpath (e.g. in tests).
 */
object SnapshotWriter {

    /** One scanned class together with the key of the artifact that owns it. */
    data class ArtifactClass(val artifactKey: String, val facts: ClassFacts)

    fun writeClassFacts(dir: Path, classes: List<ArtifactClass>, codec: CompressionCodec = CompressionCodec.ZSTD) {
        writeTable(dir.resolve(SnapshotSchema.TYPES_FILE), SnapshotSchema.typesSchema(), codec, classes) { sb, c ->
            sb.setString(SnapshotSchema.Types.ARTIFACT, c.artifactKey)
            sb.setString(SnapshotSchema.Types.FQN, c.facts.fqn)
            sb.setString(SnapshotSchema.Types.KIND, c.facts.kind.name)
            sb.setNullable(SnapshotSchema.Types.SOURCE_FILE, c.facts.sourceFile)
            sb.setNullable(SnapshotSchema.Types.CLASS_HASH, c.facts.classHash.ifEmpty { null })
        }

        val members = classes.flatMap { c -> c.facts.members.map { c.facts.fqn to it } }
        writeTable(dir.resolve(SnapshotSchema.MEMBERS_FILE), SnapshotSchema.membersSchema(), codec, members) { sb, (owner, m) ->
            sb.setString(SnapshotSchema.Members.OWNER_FQN, owner)
            sb.setString(SnapshotSchema.Members.MEMBER_KEY, m.key)
            sb.setString(SnapshotSchema.Members.KIND, m.kind.name)
            sb.setString(SnapshotSchema.Members.NAME, m.name)
            sb.setString(SnapshotSchema.Members.SIGNATURE, m.signature)
            sb.setNullableInt(SnapshotSchema.Members.LINE_FROM, m.lineFrom)
            sb.setNullableInt(SnapshotSchema.Members.LINE_TO, m.lineTo)
        }

        val refs = classes.flatMap { c -> c.facts.refs.map { c.facts.fqn to it } }
        writeTable(dir.resolve(SnapshotSchema.REFS_FILE), SnapshotSchema.refsSchema(), codec, refs) { sb, (fromType, r) ->
            sb.setString(SnapshotSchema.Refs.FROM_KEY, r.fromKey)
            sb.setString(SnapshotSchema.Refs.FROM_TYPE, fromType)
            sb.setString(SnapshotSchema.Refs.TO_KEY, r.toKey)
            sb.setString(SnapshotSchema.Refs.KIND, r.kind.name)
            sb.setNullableInt(SnapshotSchema.Refs.COUNT, r.count)
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
