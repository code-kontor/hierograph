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
package io.hierograph.graphstore.jqa

import io.hierograph.boltclient.IBoltClient
import io.hierograph.graphstore.HierarchyRow
import io.hierograph.graphstore.TypeDependency
import io.hierograph.mcp.jqa.hierarchicalgraph.fwk.GraphDbMapping
import io.hierograph.mcp.jqa.hierarchicalgraph.jQAssistantMapping

/** The jQAssistant projection, as FQN-keyed rows ready to diff against the GraphStore. */
data class ReferenceProjection(
    val hierarchy: List<HierarchyRow>,
    val dependencies: List<TypeDependency>,
)

/**
 * Builds the reference projection by running the existing jQAssistant [GraphDbMapping] rules directly
 * against a live store and resolving Neo4j ids to FQNs — the comparison baseline for [SnapshotDiff].
 *
 * It executes the *same* Cypher the production `MappingDrivenHierarchyProvider` /
 * `MappingDrivenDependencyProvider` run, reading each rule's columns by position:
 *  - top-level rules return `id, name, fqn`;
 *  - parent-child rules return `parentId, childId, name, fqn`;
 *  - the dependency rule returns `startId, targetId, relId, type, weight, extends, implements, annotatedBy, other`.
 * The rule's `kind` (a `JavaKinds` value, exactly what the GraphStore uses) becomes the row kind.
 */
class GraphDbMappingReference(
    private val boltClient: IBoltClient,
    private val mapping: GraphDbMapping = jQAssistantMapping,
) {

    fun build(): ReferenceProjection {
        val idToFqn = HashMap<Long, String>()

        // Top-level nodes: id -> fqn, emitted as roots (no parent).
        data class TopLevel(val kind: Any, val fqn: String)
        val topLevel = mapping.topLevelRules.flatMap { rule ->
            records(rule.cypher).map { r ->
                val id = r.long(0)
                val fqn = r.string(2)
                idToFqn[id] = fqn
                TopLevel(rule.kind, fqn)
            }
        }

        // Parent-child edges: capture child id -> fqn first, resolve parents afterwards so every parent
        // id (artifact or package) is already known.
        data class ParentChild(val kind: Any, val childFqn: String, val parentId: Long)
        val parentChild = mapping.parentChildRules.flatMap { rule ->
            records(rule.cypher).map { r ->
                val childId = r.long(1)
                val childFqn = r.string(3)
                idToFqn[childId] = childFqn
                ParentChild(rule.childKind, childFqn, r.long(0))
            }
        }

        val hierarchy = buildList {
            topLevel.forEach { add(HierarchyRow(it.kind, it.fqn, parentFqn = null)) }
            parentChild.forEach { add(HierarchyRow(it.kind, it.childFqn, parentFqn = fqnOf(it.parentId, idToFqn))) }
        }

        val dependencies = mapping.dependencyRules.flatMap { rule ->
            records(rule.cypher).map { r ->
                TypeDependency(
                    from = fqnOf(r.long(0), idToFqn),
                    to = fqnOf(r.long(1), idToFqn),
                    weight = r.int(4),
                    extends = r.boolean(5),
                    implements = r.boolean(6),
                    annotatedBy = r.boolean(7),
                    other = r.boolean(8),
                )
            }
        }

        return ReferenceProjection(hierarchy, dependencies)
    }

    /** Resolves a node id to its FQN from the captured map, falling back to a direct node lookup. */
    private fun fqnOf(id: Long, idToFqn: Map<Long, String>): String =
        idToFqn[id] ?: boltClient.getNode(id).get("fqn").let { if (it.isNull) "" else it.asString() }

    private fun records(cypher: String): List<RecordView> =
        boltClient.syncExecCypherQuery(cypher).records().map { RecordView(it) }

    private class RecordView(private val record: org.neo4j.driver.Record) {
        fun long(i: Int): Long = record.get(i).asLong()
        fun int(i: Int): Int = record.get(i).asInt()
        fun boolean(i: Int): Boolean = record.get(i).asBoolean()
        fun string(i: Int): String = record.get(i).let { if (it.isNull) "" else it.asString() }
    }
}
