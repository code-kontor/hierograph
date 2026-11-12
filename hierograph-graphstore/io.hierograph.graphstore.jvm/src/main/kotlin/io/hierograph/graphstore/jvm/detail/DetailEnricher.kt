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
package io.hierograph.graphstore.jvm.detail

import dev.hardwood.reader.FilterPredicate
import dev.hardwood.reader.ParquetFileReader
import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.model.DetailRef
import io.hierograph.graphstore.jvm.parquet.ParquetSchema
import io.hierograph.graphstore.jvm.parquet.SnapshotSources
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.JavaNodeKind
import java.nio.file.Files
import java.nio.file.Path

/** Display metadata for one node referenced by a detail edge — what the key *is*. */
data class NodeDisplay(val name: String, val kind: String, val qualifiedName: String)

/**
 * Turns the key-only edges of a [DetailQueryEngine] result into a `key → NodeDisplay` map (the response's
 * `nodes` block). Member display (`name`, `kind`) is read from `members.parquet` scoped to just the keys
 * the page references; type display comes from the in-memory type graph, with a simple-name / external
 * fallback for unresolved type references. See the "Member Display Enrichment" design note.
 *
 * Enrich **one page at a time**: the members read is bounded by the page's referenced keys, never a
 * full-file scan.
 */
class DetailEnricher(private val store: GraphStore, private val membersDir: Path) {

    fun nodesFor(edges: Collection<DetailRef>): Map<String, NodeDisplay> {
        val memberKeys = linkedSetOf<String>()
        val typeFqns = linkedSetOf<String>()
        for (e in edges) {
            classify(e.fromKey, memberKeys, typeFqns)
            classify(e.toKey, memberKeys, typeFqns)
            typeFqns += e.fromType
            typeFqns += e.toType
        }

        val memberKinds = MemberKindReader.readByKeys(membersDir, memberKeys)

        val nodes = LinkedHashMap<String, NodeDisplay>()
        for (key in memberKeys) {
            val kind = memberKinds[key]
            nodes[key] = if (kind != null) {
                // Named and kinded as in the containment tree: by signature, a constructor as a method.
                NodeDisplay(key.substringAfterLast('#'), memberKindDisplay(kind), key)
            } else {
                // A referenced member we never scanned (e.g. a member of an external type): best-effort.
                NodeDisplay(key.substringAfterLast('#'), "unknown", key)
            }
        }
        for (fqn in typeFqns) {
            // dependency rows' endpoints are bare FQNs; resolve display off the by-FQN type index.
            val node = store.typeByFqn(fqn)
            nodes[fqn] = if (node != null) {
                NodeDisplay(node.name, typeKindDisplay(node.kind), fqn)
            } else {
                NodeDisplay(fqn.substringAfterLast('.'), JavaKinds.EXTERNAL_TYPE, fqn)
            }
        }
        return nodes
    }

    private fun classify(key: String, members: MutableSet<String>, types: MutableSet<String>) {
        if ('#' in key) members += key else types += key
    }

    /** Raw members-table kind name (METHOD / FIELD / CONSTRUCTOR) → the tree's kind: a constructor is a method. */
    private fun memberKindDisplay(kindName: String): String =
        runCatching { JavaNodeKind.valueOf(kindName) }.getOrNull()
            ?.let { if (it == JavaNodeKind.CONSTRUCTOR) JavaNodeKind.METHOD else it }?.value ?: kindName

    /** A type node's kind (a JavaNodeKind, or the EXTERNAL_TYPE string) → display value. */
    private fun typeKindDisplay(kind: Any): String = (kind as? JavaNodeKind)?.value ?: kind.toString()
}

/**
 * Reads the kind of a bounded set of member keys from `members.parquet` across every artifact of the
 * snapshot at [membersDir][readByKeys], via push-down.
 */
internal object MemberKindReader {

    fun readByKeys(membersDir: Path, keys: Collection<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        val out = HashMap<String, String>(keys.size * 2)
        for (source in SnapshotSources.of(membersDir)) {
            val input = source.table(ParquetSchema.MEMBERS_FILE) ?: continue
            ParquetFileReader.open(input).use { reader ->
                reader.buildRowReader()
                    .filter(FilterPredicate.`in`(ParquetSchema.Members.MEMBER_KEY, *keys.toTypedArray()))
                    .build().use { rowReader ->
                        val schema = reader.fileSchema
                        val iKey = schema.getColumn(ParquetSchema.Members.MEMBER_KEY).columnIndex()
                        val iKind = schema.getColumn(ParquetSchema.Members.KIND).columnIndex()
                        while (rowReader.hasNext()) {
                            rowReader.next()
                            out[rowReader.getString(iKey)] = rowReader.getString(iKind)
                        }
                    }
            }
        }
        return out
    }
}
