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
package io.hierograph.graphstore.jvm.archive

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
 * Writes [ParquetSchema] tables row by row with Hardwood's writer, uncompressed so the tests need no
 * compression codec on the classpath. Unlike [ParquetWriter] it can write tables the production writer
 * never produces — a table without a newer column, a format-1 table, a table of another format version —
 * which is what tests of [ParquetReader] and of the loaders need. Shared with the store's tests through
 * this module's test-jar.
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

    /** One dependency row; [fromKey] defaults to [fromType], a type-level fact. */
    data class DependencyFixture(
        val fromType: String,
        val toType: String,
        val relationship: String,
        val fromKey: String = fromType,
        val toKey: String = toType,
        val line: Int? = null,
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

    fun writeDependencies(dir: Path, rows: List<DependencyFixture>) {
        val cols = ParquetSchema.Dependencies
        write(dir.resolve(ParquetSchema.DEPENDENCIES_FILE), ParquetSchema.dependenciesSchema(), rows.sortedBy { it.fromType }) { sb, d ->
            sb.setString(cols.FROM_KEY, d.fromKey)
            sb.setString(cols.FROM_TYPE, d.fromType)
            sb.setString(cols.TO_KEY, d.toKey)
            sb.setString(cols.TO_TYPE, d.toType)
            sb.setString(cols.RELATIONSHIP, d.relationship)
            sb.setNullableInt(cols.LINE, d.line)
        }
    }

    /** Writes a table of format 1, which a reader must reject: here an (empty) `refs.parquet`. */
    fun writeFormat1Refs(dir: Path) {
        val schema = FileSchema.builder("ref")
            .addColumn("from_type", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED, LogicalType.string())
            .build()
        write(dir.resolve("refs.parquet"), schema, emptyList<Unit>()) { _, _ -> }
    }

    /** Writes `types.parquet` with [version] in its footer, or without a version when it is `null`. */
    fun writeTypesInFormat(dir: Path, version: String?, rows: List<TypeFixture>) {
        write(dir.resolve(ParquetSchema.TYPES_FILE), ParquetSchema.typesSchema(), rows, version) { sb, t ->
            sb.setString(ParquetSchema.Types.ARTIFACT, t.artifact)
            sb.setString(ParquetSchema.Types.FQN, t.fqn)
            sb.setString(ParquetSchema.Types.KIND, t.kind)
            sb.setNullable(ParquetSchema.Types.SOURCE_FILE, t.sourceFile)
            sb.setNullable(ParquetSchema.Types.CLASS_HASH, t.classHash)
        }
    }

    private fun <T> write(
        path: Path,
        schema: FileSchema,
        rows: List<T>,
        version: String? = ParquetSchema.FORMAT_VERSION.toString(),
        set: (StructBuilder, T) -> Unit,
    ) {
        val config = WriterConfig.builder().codec(CompressionCodec.UNCOMPRESSED).build()
        ParquetFileWriter.create(OutputFile.of(path), schema, config).use { writer ->
            version?.let { writer.keyValueMetadata(ParquetSchema.FORMAT_VERSION_KEY, it) }
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
