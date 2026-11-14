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

import dev.hardwood.metadata.LogicalType
import dev.hardwood.metadata.PhysicalType
import dev.hardwood.metadata.RepetitionType
import dev.hardwood.schema.FileSchema

/**
 * The flat table layout of a GraphStore Parquet snapshot, shared by the loader and the writer so both
 * sides agree on file names, column names and types.
 *
 * This is the MVP schema (spec §7): kinds are stored as **strings** (the enum names `CLASS`, `METHOD`,
 * `EXTENDS`, …) rather than the int32 ordinals of the full §4 schema, which keeps snapshots human-readable. A single snapshot can mix
 * artifacts, so `types` carries an explicit `artifact` column.
 */
object ParquetSchema {

    const val TYPES_FILE = "types.parquet"
    const val MEMBERS_FILE = "members.parquet"
    const val PARAMETERS_FILE = "parameters.parquet"
    const val REFS_FILE = "refs.parquet"
    const val DETAIL_REFS_FILE = "detail_refs.parquet"
    const val CLASSPATH_FILE = "classpath.parquet" // per-artifact classpath manifest; absent in CLI/legacy snapshots

    object Types {
        const val ARTIFACT = "artifact"
        const val FQN = "fqn"
        const val KIND = "kind"                 // JavaNodeKind name: CLASS | INTERFACE | ENUM | ANNOTATION | RECORD
        const val SOURCE_FILE = "source_file"
        const val CLASS_HASH = "class_hash"
        const val MODIFIERS = "modifiers"       // packed access flags; see TypeModifiers. Absent in older snapshots.
    }

    object Members {
        const val OWNER_FQN = "owner_fqn"
        const val MEMBER_KEY = "member_key"     // owner_fqn + '#' + signature
        const val KIND = "kind"                 // JavaNodeKind name: FIELD | METHOD | CONSTRUCTOR
        const val NAME = "name"
        const val SIGNATURE = "signature"
        const val MODIFIERS = "modifiers"       // packed access flags; see MemberModifiers
        const val LINE_FROM = "line_from"
        const val LINE_TO = "line_to"
        const val BRIDGE_TARGET = "bridge_target" // for a synthetic bridge: the real method it forwards to. Absent in older snapshots.
    }

    /** Declared method/constructor parameters, one row per parameter, sorted by `method_key, index`. */
    object Parameters {
        const val METHOD_KEY = "method_key"     // the owning member's key: owner_fqn + '#' + signature
        const val INDEX = "index"               // 0-based position
        const val NAME = "name"
        const val TYPE_FQN = "type_fqn"         // fully-qualified (erased) parameter type
        const val ANNOTATIONS = "annotations"   // the parameter's annotation types, space-separated; null for none
    }

    object Refs {
        // Type-level refs are type→type: both endpoints are types, so the columns are `from_type` /
        // `to_type`. The member-grained `from_key` / `to_key` of the full §4 schema are omitted (they
        // would equal the type FQNs); member-grained refs live in [DetailRefs] until the two merge
        // (see the "type-level vs detail refs" decision).
        const val FROM_TYPE = "from_type"
        const val TO_TYPE = "to_type"
        const val KIND = "kind"                 // RefKind name
        const val COUNT = "count"
    }

    /**
     * Member-level ("detail") references. Prototype table (written to its own `detail_refs.parquet`) so
     * the validated type-level snapshot stays untouched; in the production model these rows merge into
     * [Refs] (with `to_type` / `line` added, distinguished by kind). Sorted by `from_type` so scoped
     * reads skip row groups.
     */
    object DetailRefs {
        const val FROM_KEY = "from_key"
        const val FROM_TYPE = "from_type"
        const val TO_KEY = "to_key"
        const val TO_TYPE = "to_type"
        const val RELATIONSHIP = "relationship" // Hierograph detail vocabulary: calls | reads_field | …
        const val LINE = "line"
        const val PARAMETER_INDEX = "parameter_index" // parameter_type / parameter_annotated_by only; added later, optional
        const val PARAMETER_NAME = "parameter_name"   // set exactly when parameter_index is
    }

    /**
     * The owning artifact's classpath, one flat row per entry (the Maven plugin's manifest). `index 0` is
     * the artifact itself, then its dependencies in resolution order; `artifact_key`/`name` repeat on every
     * row. Drives load-time classpath-shadowing resolution (see `TypeResolver`); absent in CLI snapshots.
     */
    object Classpath {
        const val ARTIFACT_KEY = "artifact_key" // the owning artifact's GAV (same on all its rows)
        const val NAME = "name"                 // the owning artifact's display name (artifactId)
        const val INDEX = "index"               // 0 = the artifact itself, then deps in resolution order
        const val DEP_KEY = "dep_key"           // GAV of the classpath entry at this index
        const val INTERNAL = "internal"         // true = a scanned reactor sibling, false = external dependency
    }

    fun typesSchema(): FileSchema =
        FileSchema.builder("type")
            .string(Types.ARTIFACT, RepetitionType.REQUIRED)
            .string(Types.FQN, RepetitionType.REQUIRED)
            .string(Types.KIND, RepetitionType.REQUIRED)
            .string(Types.SOURCE_FILE, RepetitionType.OPTIONAL)
            .string(Types.CLASS_HASH, RepetitionType.OPTIONAL)
            .addColumn(Types.MODIFIERS, PhysicalType.INT32, RepetitionType.OPTIONAL)
            .build()

    fun membersSchema(): FileSchema =
        FileSchema.builder("member")
            .string(Members.OWNER_FQN, RepetitionType.REQUIRED)
            .string(Members.MEMBER_KEY, RepetitionType.REQUIRED)
            .string(Members.KIND, RepetitionType.REQUIRED)
            .string(Members.NAME, RepetitionType.REQUIRED)
            .string(Members.SIGNATURE, RepetitionType.REQUIRED)
            .addColumn(Members.MODIFIERS, PhysicalType.INT32, RepetitionType.OPTIONAL)
            .addColumn(Members.LINE_FROM, PhysicalType.INT32, RepetitionType.OPTIONAL)
            .addColumn(Members.LINE_TO, PhysicalType.INT32, RepetitionType.OPTIONAL)
            .string(Members.BRIDGE_TARGET, RepetitionType.OPTIONAL)
            .build()

    fun parametersSchema(): FileSchema =
        FileSchema.builder("parameter")
            .string(Parameters.METHOD_KEY, RepetitionType.REQUIRED)
            .addColumn(Parameters.INDEX, PhysicalType.INT32, RepetitionType.REQUIRED)
            .string(Parameters.NAME, RepetitionType.REQUIRED)
            .string(Parameters.TYPE_FQN, RepetitionType.REQUIRED)
            .string(Parameters.ANNOTATIONS, RepetitionType.OPTIONAL)
            .build()

    fun refsSchema(): FileSchema =
        FileSchema.builder("ref")
            .string(Refs.FROM_TYPE, RepetitionType.REQUIRED)
            .string(Refs.TO_TYPE, RepetitionType.REQUIRED)
            .string(Refs.KIND, RepetitionType.REQUIRED)
            .addColumn(Refs.COUNT, PhysicalType.INT32, RepetitionType.OPTIONAL)
            .build()

    fun detailRefsSchema(): FileSchema =
        FileSchema.builder("detail_ref")
            .string(DetailRefs.FROM_KEY, RepetitionType.REQUIRED)
            .string(DetailRefs.FROM_TYPE, RepetitionType.REQUIRED)
            .string(DetailRefs.TO_KEY, RepetitionType.REQUIRED)
            .string(DetailRefs.TO_TYPE, RepetitionType.REQUIRED)
            .string(DetailRefs.RELATIONSHIP, RepetitionType.REQUIRED)
            .addColumn(DetailRefs.LINE, PhysicalType.INT32, RepetitionType.OPTIONAL)
            .addColumn(DetailRefs.PARAMETER_INDEX, PhysicalType.INT32, RepetitionType.OPTIONAL)
            .string(DetailRefs.PARAMETER_NAME, RepetitionType.OPTIONAL)
            .build()

    fun classpathSchema(): FileSchema =
        FileSchema.builder("classpath")
            .string(Classpath.ARTIFACT_KEY, RepetitionType.REQUIRED)
            .string(Classpath.NAME, RepetitionType.REQUIRED)
            .addColumn(Classpath.INDEX, PhysicalType.INT32, RepetitionType.REQUIRED)
            .string(Classpath.DEP_KEY, RepetitionType.REQUIRED)
            .addColumn(Classpath.INTERNAL, PhysicalType.INT32, RepetitionType.REQUIRED) // 0 = external, 1 = internal
            .build()

    private fun FileSchema.Builder.string(name: String, repetition: RepetitionType): FileSchema.Builder =
        addColumn(name, PhysicalType.BYTE_ARRAY, repetition, LogicalType.string())
}
