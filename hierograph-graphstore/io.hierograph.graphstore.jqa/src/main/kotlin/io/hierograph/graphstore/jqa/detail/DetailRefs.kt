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
package io.hierograph.graphstore.jqa.detail

import dev.hardwood.InputFile
import dev.hardwood.OutputFile
import dev.hardwood.metadata.CompressionCodec
import dev.hardwood.metadata.LogicalType
import dev.hardwood.metadata.PhysicalType
import dev.hardwood.metadata.RepetitionType
import dev.hardwood.reader.FilterPredicate
import dev.hardwood.reader.ParquetFileReader
import dev.hardwood.schema.FileSchema
import dev.hardwood.writer.ParquetFileWriter
import dev.hardwood.writer.WriterConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * One member-level ("detail") reference: an edge from a source **member** (or type, for class-level
 * annotations) to a target **symbol** — a member for `calls`/`reads_field`/…, a type for
 * `throws`/`returns`/`has_type`/`parameter_type`/`annotated_by`. [relationship] is the Hierograph
 * detail vocabulary; [toKey] equals [toType] when the target is a type reference.
 *
 * Prototype note: this is a dedicated `detail_refs.parquet` so the validated type-level snapshot stays
 * untouched. In the production model these rows merge into the single `refs` table, distinguished by
 * kind (see the "Types vs Type References" model doc).
 */
data class DetailRef(
    val fromKey: String,
    val fromType: String,
    val toKey: String,
    val toType: String,
    val relationship: String,
    val line: Int? = null,
)

/**
 * Parquet I/O for the detail-refs table. Rows are written sorted by `from_type` so a scoped read
 * (`from_type ∈ set`) hits a contiguous run of row groups and Hardwood's min/max statistics skip the
 * rest of the file — the approach-B commitment, realised via predicate push-down.
 */
object DetailRefs {

    const val FILE = "detail_refs.parquet"

    const val FROM_KEY = "from_key"
    const val FROM_TYPE = "from_type"
    const val TO_KEY = "to_key"
    const val TO_TYPE = "to_type"
    const val RELATIONSHIP = "relationship"
    const val LINE = "line"

    fun schema(): FileSchema =
        FileSchema.builder("detail_ref")
            .string(FROM_KEY)
            .string(FROM_TYPE)
            .string(TO_KEY)
            .string(TO_TYPE)
            .string(RELATIONSHIP)
            .addColumn(LINE, PhysicalType.INT32, RepetitionType.OPTIONAL)
            .build()

    /** Writes [refs] (sorted by `from_type`, then `from_key`) to `detail_refs.parquet` under [dir]. */
    fun write(dir: Path, refs: List<DetailRef>, codec: CompressionCodec = CompressionCodec.UNCOMPRESSED) {
        val sorted = refs.sortedWith(compareBy({ it.fromType }, { it.fromKey }, { it.toType }, { it.toKey }, { it.relationship }))
        val config = WriterConfig.builder().codec(codec).build()
        ParquetFileWriter.create(OutputFile.of(dir.resolve(FILE)), schema(), config).use { writer ->
            val rowWriter = writer.rowWriter()
            for (r in sorted) {
                rowWriter.writeRow { sb ->
                    sb.setString(FROM_KEY, r.fromKey)
                    sb.setString(FROM_TYPE, r.fromType)
                    sb.setString(TO_KEY, r.toKey)
                    sb.setString(TO_TYPE, r.toType)
                    sb.setString(RELATIONSHIP, r.relationship)
                    if (r.line == null) sb.setNull(LINE) else sb.setInt(LINE, r.line)
                }
            }
            writer.endRowGroup()
        }
    }

    /**
     * Scoped read with predicate push-down: returns the detail refs whose `from_type` is in [fromTypes]
     * (unconstrained when null/empty), whose `to_type` is in [toTypes] (unconstrained when null/empty),
     * and whose `relationship` matches [relationship] (any when null). Hardwood skips row groups outside
     * the `from_type` range via column statistics; the `to_type` predicate still filters rows but, since
     * `to_type` is not the sort key, does not drive row-group skipping.
     */
    fun read(
        dir: Path,
        fromTypes: Collection<String>? = null,
        toTypes: Collection<String>? = null,
        relationship: String? = null,
    ): List<DetailRef> {
        val path = dir.resolve(FILE)
        if (!Files.exists(path)) return emptyList()
        val out = ArrayList<DetailRef>()
        ParquetFileReader.open(InputFile.of(path)).use { reader ->
            val builder = reader.buildRowReader()
            buildFilter(fromTypes, toTypes, relationship)?.let { builder.filter(it) }
            builder.build().use { rowReader ->
                val schema = reader.fileSchema
                fun idx(name: String) = schema.getColumn(name).columnIndex()
                val iFromKey = idx(FROM_KEY); val iFromType = idx(FROM_TYPE)
                val iToKey = idx(TO_KEY); val iToType = idx(TO_TYPE)
                val iRel = idx(RELATIONSHIP); val iLine = idx(LINE)
                while (rowReader.hasNext()) {
                    rowReader.next()
                    out += DetailRef(
                        fromKey = rowReader.getString(iFromKey),
                        fromType = rowReader.getString(iFromType),
                        toKey = rowReader.getString(iToKey),
                        toType = rowReader.getString(iToType),
                        relationship = rowReader.getString(iRel),
                        line = if (rowReader.isNull(iLine)) null else rowReader.getInt(iLine),
                    )
                }
            }
        }
        return out
    }

    private fun buildFilter(fromTypes: Collection<String>?, toTypes: Collection<String>?, relationship: String?): FilterPredicate? {
        val preds = buildList {
            if (!fromTypes.isNullOrEmpty()) add(FilterPredicate.`in`(FROM_TYPE, *fromTypes.toTypedArray()))
            if (!toTypes.isNullOrEmpty()) add(FilterPredicate.`in`(TO_TYPE, *toTypes.toTypedArray()))
            if (relationship != null) add(FilterPredicate.eq(RELATIONSHIP, relationship))
        }
        return when (preds.size) {
            0 -> null
            1 -> preds[0]
            else -> FilterPredicate.and(*preds.toTypedArray())
        }
    }

    private fun FileSchema.Builder.string(name: String): FileSchema.Builder =
        addColumn(name, PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED, LogicalType.string())
}
