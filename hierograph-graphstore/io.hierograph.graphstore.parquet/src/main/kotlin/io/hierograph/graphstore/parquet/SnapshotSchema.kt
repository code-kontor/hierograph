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

import dev.hardwood.metadata.LogicalType
import dev.hardwood.metadata.PhysicalType
import dev.hardwood.metadata.RepetitionType
import dev.hardwood.schema.FileSchema

/**
 * The flat table layout of a GraphStore Parquet snapshot, shared by the loader and the writer so both
 * sides agree on file names, column names and types.
 *
 * This is the MVP schema (spec §7): kinds are stored as **strings** (the enum names `CLASS`, `METHOD`,
 * `EXTENDS`, …) rather than the int32 ordinals of the full §4 schema, which keeps it identical to what
 * the jQAssistant CSV export produces and makes snapshots human-readable. A single snapshot can mix
 * artifacts, so `types` carries an explicit `artifact` column.
 */
object SnapshotSchema {

    const val TYPES_FILE = "types.parquet"
    const val MEMBERS_FILE = "members.parquet"
    const val REFS_FILE = "refs.parquet"

    object Types {
        const val ARTIFACT = "artifact"
        const val FQN = "fqn"
        const val KIND = "kind"                 // JavaNodeKind name: CLASS | INTERFACE | ENUM | ANNOTATION | RECORD
        const val SOURCE_FILE = "source_file"
        const val CLASS_HASH = "class_hash"
    }

    object Members {
        const val OWNER_FQN = "owner_fqn"
        const val MEMBER_KEY = "member_key"     // owner_fqn + '#' + signature
        const val KIND = "kind"                 // JavaNodeKind name: FIELD | METHOD | CONSTRUCTOR
        const val NAME = "name"
        const val SIGNATURE = "signature"
        const val LINE_FROM = "line_from"
        const val LINE_TO = "line_to"
    }

    object Refs {
        const val FROM_KEY = "from_key"         // type fqn or member key
        const val FROM_TYPE = "from_type"       // owning type, denormalized
        const val TO_KEY = "to_key"
        const val KIND = "kind"                 // RefKind name
        const val COUNT = "count"
    }

    fun typesSchema(): FileSchema =
        FileSchema.builder("type")
            .string(Types.ARTIFACT, RepetitionType.REQUIRED)
            .string(Types.FQN, RepetitionType.REQUIRED)
            .string(Types.KIND, RepetitionType.REQUIRED)
            .string(Types.SOURCE_FILE, RepetitionType.OPTIONAL)
            .string(Types.CLASS_HASH, RepetitionType.OPTIONAL)
            .build()

    fun membersSchema(): FileSchema =
        FileSchema.builder("member")
            .string(Members.OWNER_FQN, RepetitionType.REQUIRED)
            .string(Members.MEMBER_KEY, RepetitionType.REQUIRED)
            .string(Members.KIND, RepetitionType.REQUIRED)
            .string(Members.NAME, RepetitionType.REQUIRED)
            .string(Members.SIGNATURE, RepetitionType.REQUIRED)
            .addColumn(Members.LINE_FROM, PhysicalType.INT32, RepetitionType.OPTIONAL)
            .addColumn(Members.LINE_TO, PhysicalType.INT32, RepetitionType.OPTIONAL)
            .build()

    fun refsSchema(): FileSchema =
        FileSchema.builder("ref")
            .string(Refs.FROM_KEY, RepetitionType.REQUIRED)
            .string(Refs.FROM_TYPE, RepetitionType.REQUIRED)
            .string(Refs.TO_KEY, RepetitionType.REQUIRED)
            .string(Refs.KIND, RepetitionType.REQUIRED)
            .addColumn(Refs.COUNT, PhysicalType.INT32, RepetitionType.OPTIONAL)
            .build()

    private fun FileSchema.Builder.string(name: String, repetition: RepetitionType): FileSchema.Builder =
        addColumn(name, PhysicalType.BYTE_ARRAY, repetition, LogicalType.string())
}
