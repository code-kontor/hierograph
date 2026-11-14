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
package io.hierograph.graphstore.jvm.parquet

import dev.hardwood.OutputFile
import dev.hardwood.metadata.CompressionCodec
import dev.hardwood.metadata.LogicalType
import dev.hardwood.metadata.PhysicalType
import dev.hardwood.metadata.RepetitionType
import dev.hardwood.schema.FileSchema
import dev.hardwood.writer.ParquetFileWriter
import dev.hardwood.writer.StructBuilder
import dev.hardwood.writer.WriterConfig
import java.nio.file.Path

/**
 * Writes [ParquetSchema] Parquet fixtures using Hardwood's writer, uncompressed so the tests need no
 * compression codec on the classpath. Test-only scaffolding for exercising [ParquetReader]; the
 * production snapshot writer is a separate, later concern.
 */
object ParquetTestWriter {

    data class TypeFixture(
        val artifact: String,
        val fqn: String,
        val kind: String,
        val sourceFile: String? = null,
        val classHash: String? = null,
    )

    data class MemberFixture(
        val ownerFqn: String,
        val memberKey: String,
        val kind: String,
        val name: String,
        val signature: String,
        val lineFrom: Int? = null,
        val lineTo: Int? = null,
    )

    data class RefFixture(
        val fromType: String,
        val toType: String,
        val kind: String,
        val count: Int? = null,
    )

    /** Writes `types.parquet` in the layout used before the `modifiers` column existed (an older snapshot). */
    fun writeLegacyTypes(dir: Path, rows: List<TypeFixture>) {
        fun FileSchema.Builder.str(name: String, rep: RepetitionType) =
            addColumn(name, PhysicalType.BYTE_ARRAY, rep, LogicalType.string())
        val legacySchema = FileSchema.builder("type")
            .str(ParquetSchema.Types.ARTIFACT, RepetitionType.REQUIRED)
            .str(ParquetSchema.Types.FQN, RepetitionType.REQUIRED)
            .str(ParquetSchema.Types.KIND, RepetitionType.REQUIRED)
            .str(ParquetSchema.Types.SOURCE_FILE, RepetitionType.OPTIONAL)
            .str(ParquetSchema.Types.CLASS_HASH, RepetitionType.OPTIONAL)
            .build()
        write(dir.resolve(ParquetSchema.TYPES_FILE), legacySchema, rows) { sb, t ->
            sb.setString(ParquetSchema.Types.ARTIFACT, t.artifact)
            sb.setString(ParquetSchema.Types.FQN, t.fqn)
            sb.setString(ParquetSchema.Types.KIND, t.kind)
            sb.setNullable(ParquetSchema.Types.SOURCE_FILE, t.sourceFile)
            sb.setNullable(ParquetSchema.Types.CLASS_HASH, t.classHash)
        }
    }

    /**
     * Writes `detail_refs.parquet` in the layout used before the `parameter_index` / `parameter_name`
     * columns existed (an older snapshot). The rows' parameter fields are not written.
     */
    fun writeLegacyDetailRefs(dir: Path, rows: List<io.hierograph.graphstore.jvm.model.DetailRef>) {
        fun FileSchema.Builder.str(name: String, rep: RepetitionType) =
            addColumn(name, PhysicalType.BYTE_ARRAY, rep, LogicalType.string())
        val cols = ParquetSchema.DetailRefs
        val legacySchema = FileSchema.builder("detail_ref")
            .str(cols.FROM_KEY, RepetitionType.REQUIRED)
            .str(cols.FROM_TYPE, RepetitionType.REQUIRED)
            .str(cols.TO_KEY, RepetitionType.REQUIRED)
            .str(cols.TO_TYPE, RepetitionType.REQUIRED)
            .str(cols.RELATIONSHIP, RepetitionType.REQUIRED)
            .addColumn(cols.LINE, PhysicalType.INT32, RepetitionType.OPTIONAL)
            .build()
        write(dir.resolve(ParquetSchema.DETAIL_REFS_FILE), legacySchema, rows) { sb, r ->
            sb.setString(cols.FROM_KEY, r.fromKey)
            sb.setString(cols.FROM_TYPE, r.fromType)
            sb.setString(cols.TO_KEY, r.toKey)
            sb.setString(cols.TO_TYPE, r.toType)
            sb.setString(cols.RELATIONSHIP, r.relationship)
            sb.setNullableInt(cols.LINE, r.line)
        }
    }

    fun writeTypes(dir: Path, rows: List<TypeFixture>) {
        write(dir.resolve(ParquetSchema.TYPES_FILE), ParquetSchema.typesSchema(), rows) { sb, t ->
            sb.setString(ParquetSchema.Types.ARTIFACT, t.artifact)
            sb.setString(ParquetSchema.Types.FQN, t.fqn)
            sb.setString(ParquetSchema.Types.KIND, t.kind)
            sb.setNullable(ParquetSchema.Types.SOURCE_FILE, t.sourceFile)
            sb.setNullable(ParquetSchema.Types.CLASS_HASH, t.classHash)
        }
    }

    fun writeMembers(dir: Path, rows: List<MemberFixture>) {
        write(dir.resolve(ParquetSchema.MEMBERS_FILE), ParquetSchema.membersSchema(), rows) { sb, m ->
            sb.setString(ParquetSchema.Members.OWNER_FQN, m.ownerFqn)
            sb.setString(ParquetSchema.Members.MEMBER_KEY, m.memberKey)
            sb.setString(ParquetSchema.Members.KIND, m.kind)
            sb.setString(ParquetSchema.Members.NAME, m.name)
            sb.setString(ParquetSchema.Members.SIGNATURE, m.signature)
            sb.setNullableInt(ParquetSchema.Members.LINE_FROM, m.lineFrom)
            sb.setNullableInt(ParquetSchema.Members.LINE_TO, m.lineTo)
        }
    }

    fun writeRefs(dir: Path, rows: List<RefFixture>) {
        write(dir.resolve(ParquetSchema.REFS_FILE), ParquetSchema.refsSchema(), rows) { sb, r ->
            sb.setString(ParquetSchema.Refs.FROM_TYPE, r.fromType)
            sb.setString(ParquetSchema.Refs.TO_TYPE, r.toType)
            sb.setString(ParquetSchema.Refs.KIND, r.kind)
            sb.setNullableInt(ParquetSchema.Refs.COUNT, r.count)
        }
    }

    private fun <T> write(path: Path, schema: FileSchema, rows: List<T>, set: (StructBuilder, T) -> Unit) {
        val config = WriterConfig.builder().codec(CompressionCodec.UNCOMPRESSED).build()
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
