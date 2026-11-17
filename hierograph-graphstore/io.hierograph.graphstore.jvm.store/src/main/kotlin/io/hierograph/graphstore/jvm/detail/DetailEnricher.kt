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
import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.ParquetReader
import io.hierograph.graphstore.jvm.archive.ParquetSchema
import io.hierograph.graphstore.jvm.archive.SnapshotSources
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
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

    companion object {
        /** The display kind of a member no scanned type declares. */
        const val EXTERNAL_MEMBER = "external.member"
    }

    private val reader = ParquetReader()

    fun nodesFor(edges: Collection<Dependency>): Map<String, NodeDisplay> {
        val memberKeys = linkedSetOf<String>()
        val typeFqns = linkedSetOf<String>()
        for (e in edges) {
            classify(e.fromKey, memberKeys, typeFqns)
            classify(e.toKey, memberKeys, typeFqns)
            typeFqns += e.fromType
            typeFqns += e.toType
        }

        val memberKinds = memberKinds(memberKeys)

        val nodes = LinkedHashMap<String, NodeDisplay>()
        for (key in memberKeys) {
            val kind = memberKinds[key]
            nodes[key] = if (kind != null) {
                // Named and kinded as in the containment tree: by signature, a constructor as a method.
                NodeDisplay(key.substringAfterLast('#'), memberKindDisplay(kind), key)
            } else {
                // A member no scanned type declares: one of an external type, or one a scanned type inherits
                // from a supertype outside the scanned code (`Throwable#getMessage()`).
                NodeDisplay(key.substringAfterLast('#'), EXTERNAL_MEMBER, key)
            }
        }
        for (fqn in typeFqns) {
            // dependency rows' endpoints are bare FQNs; resolve display off the by-FQN type index.
            val node = store.typeByFqn(fqn)
            nodes[fqn] = if (node != null) {
                NodeDisplay(node.name, typeKindDisplay(node.kind), fqn)
            } else {
                NodeDisplay(fqn.substringAfterLast('.'), JavaNodeKind.EXTERNAL_TYPE, fqn)
            }
        }
        return nodes
    }

    private fun classify(key: String, members: MutableSet<String>, types: MutableSet<String>) {
        if ('#' in key) members += key else types += key
    }

    /** A member's kind → the tree's kind: a constructor is a method. */
    private fun memberKindDisplay(kind: JavaNodeKind): String =
        (if (kind == JavaNodeKind.CONSTRUCTOR) JavaNodeKind.METHOD else kind).value

    /**
     * The kind of each of [keys] that `members.parquet` holds, read with push-down across every artifact of
     * the snapshot; a key no artifact declares is absent. Read through [ParquetReader], so a kind outside the
     * vocabulary is rejected like everywhere else.
     */
    private fun memberKinds(keys: Collection<String>): Map<String, JavaNodeKind> {
        if (keys.isEmpty()) return emptyMap()
        val filter = FilterPredicate.`in`(ParquetSchema.Members.MEMBER_KEY, *keys.toTypedArray())
        return SnapshotSources.of(membersDir)
            .flatMap { source -> reader.readMembers(source, filter).values.flatten() }
            .associate { it.key to it.kind }
    }

    /** A type node's kind (a JavaNodeKind, or the EXTERNAL_TYPE string) → display value. */
    private fun typeKindDisplay(kind: Any): String = (kind as? JavaNodeKind)?.value ?: kind.toString()
}
