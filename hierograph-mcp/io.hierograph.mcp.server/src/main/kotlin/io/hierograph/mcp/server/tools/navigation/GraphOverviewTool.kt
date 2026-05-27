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
package io.hierograph.mcp.server.tools.navigation

import io.hierograph.hierarchicalgraph.core.model.HGNodeTraverser
import io.hierograph.mcp.server.core.HierarchicalGraphService
import io.hierograph.mcp.javaspec.JavaEdgeAttributes
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.javaspec.JavaNodeKind
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

/**
 * MCP tool: `graph_overview`
 *
 * Returns a structural overview of the loaded codebase: statistics, the kind and
 * relationship vocabularies, top-level module structure, and the zoom-level model.
 * No parameters — always describes the full loaded graph.
 */
@Component
class GraphOverviewTool(
    private val graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory
) {

    @Tool(
        name = "graph_overview",
        description = "[Discovery and navigation] " +
                "Return a structural overview of the loaded codebase. This is the orientation " +
                "tool — call it first when starting a new session to learn what's in the " +
                "codebase, what vocabulary the other tools use, and how dependency analysis " +
                "tools relate to each other. Returns statistics (node counts by kind, edge " +
                "counts by attribute), the kind and relationship vocabularies, top-level " +
                "module structure, and the zoom-level model. No parameters needed. For known " +
                "codebases, this can be skipped."
    )
    fun graphOverview(): Map<String, Any?> {

        val rootNode = graphService.rootNode

        // ── stats: count nodes by kind ─────────────────────────────────
        val nodesByKind = linkedMapOf<String, Int>()
        var totalNodes = 0
        for (child in rootNode.children) {
            HGNodeTraverser.traverse(child) { node ->
                val kindStr = node.kind?.toString() ?: "unknown"
                nodesByKind.merge(kindStr, 1) { a, b -> a + b }
                totalNodes++
            }
        }

        // ── stats: count type-level edges by attribute ────────────────
        val edgesByAttribute = linkedMapOf<String, Int>()
        for ((_, name) in JavaEdgeAttributes.ALL) {
            edgesByAttribute[name] = 0
        }
        var totalEdges = 0
        for (child in rootNode.children) {
            HGNodeTraverser.traverse(child) { node ->
                for (dep in node.outgoingCoreDependencies) {
                    totalEdges++
                    val bitmap = dep.attributesBitmap
                    for ((pos, name) in JavaEdgeAttributes.ALL) {
                        if (JavaEdgeAttributes.isSet(bitmap, pos)) {
                            edgesByAttribute.merge(name, 1) { a, b -> a + b }
                        }
                    }
                }
            }
        }

        // ── kinds vocabulary ───────────────────────────────────────────
        val kinds = mapOf(
            "structural" to listOf(
                kindEntry(JavaNodeKind.MODULE, "Build module (Maven/Gradle)"),
                kindEntry(JavaNodeKind.PACKAGE, "Java package; contains sub-packages and types"),
                kindEntry(JavaNodeKind.CLASS, "Class"),
                kindEntry(JavaNodeKind.INTERFACE, "Interface"),
                kindEntry(JavaNodeKind.ENUM, "Enum type"),
                kindEntry(JavaNodeKind.RECORD, "Record (Java 14+)"),
                kindEntry(JavaNodeKind.ANNOTATION, "Annotation type"),
                kindEntry(JavaNodeKind.METHOD, "Method (includes constructors)"),
                kindEntry(JavaNodeKind.FIELD, "Field")
            ),
            "group_aliases" to mapOf(
                JavaKinds.ALIAS_TYPES to JavaKinds.TYPE_KINDS.map { it.value },
                JavaKinds.ALIAS_MEMBERS to JavaKinds.MEMBER_KINDS.map { it.value },
                JavaKinds.ALIAS_PACKAGES to listOf(JavaNodeKind.PACKAGE.value)
            )
        )

        // ── relationships vocabulary ───────────────────────────────────
        val relationships = mapOf(
            "type_level_attributes" to JavaEdgeAttributes.ALL.map { (_, name) ->
                attrEntry(name)
            },
            "detail_level" to listOf(
                detailRelEntry("throws", "method", "Method declares it throws this exception type"),
                detailRelEntry("calls", "method", "Method invokes a method"),
                detailRelEntry("returns", "method", "Method's return type"),
                detailRelEntry("parameter_type", "method", "Method has a parameter of this type"),
                detailRelEntry("reads_field", "method", "Method reads a field"),
                detailRelEntry("writes_field", "method", "Method writes a field"),
                detailRelEntry("overrides", "method", "Method overrides another method"),
                detailRelEntry("annotated_by", "method/field", "Entity has this annotation type"),
                detailRelEntry("parameter_annotated_by", "method", "Method has a parameter with this annotation type"),
                detailRelEntry("has_type", "field", "Field is of this type"),
                detailRelEntry("read_by", "field", "Field is read by this method"),
                detailRelEntry("written_by", "field", "Field is written by this method")
            )
        )

        // ── hierarchy: top-level modules with enriched metadata ────────
        val hierarchy = rootNode.children.map { child ->
            val ref = nodeRefFactory.enrichedNodeRef(child)
            ref["outgoing_dep_count"] = child.accumulatedOutgoingCoreDependencies.size
            ref["incoming_dep_count"] = child.accumulatedIncomingCoreDependencies.size
            ref
        }

        // ── model: zoom-level description ──────────────────────────────
        val model = mapOf(
            "aggregation" to "Aggregation is pairwise. Given any two subtrees, aggregated_dependencies " +
                    "computes one aggregated edge between them. Provide source_ids and target_ids as sets; " +
                    "the result includes one edge per (source, target) pair that has a dependency.",
            "levels" to listOf(
                mapOf(
                    "name" to "aggregated",
                    "description" to "Pairwise rollup of dependencies between subtrees, with weight and attributes",
                    "tools" to listOf("aggregated_dependencies", "pairwise_dependencies")
                ),
                mapOf(
                    "name" to "type",
                    "description" to "Type-to-type edges between two specific subtrees, fast (in-memory)",
                    "tools" to listOf("outgoing_dependencies", "incoming_dependencies"),
                    "parameter" to "detail_level=\"type\" (default)"
                ),
                mapOf(
                    "name" to "detail",
                    "description" to "Method/field-level edges between two specific subtrees, queried on demand",
                    "tools" to listOf("outgoing_dependencies", "incoming_dependencies"),
                    "parameter" to "detail_level=\"detail\""
                )
            )
        )

        // ── scan metadata ──────────────────────────────────────────────
        val mp = rootNode.getExtension(INodeMetadataProvider::class.java)
        val scanMetadata = mapOf<String, Any?>(
            "scanner" to mp?.getScannerName()
        )

        // ── assemble response ──────────────────────────────────────────
        return linkedMapOf(
            "stats" to linkedMapOf(
                "total_nodes" to totalNodes,
                "nodes_by_kind" to nodesByKind,
                "total_type_level_edges" to totalEdges,
                "type_level_edges_by_attribute" to edgesByAttribute
            ),
            "kinds" to kinds,
            "relationships" to relationships,
            "hierarchy" to hierarchy,
            "model" to model,
            "scan_metadata" to scanMetadata
        )
    }

    private fun kindEntry(kind: JavaNodeKind, description: String) =
        mapOf("kind" to kind.value, "description" to description)

    private fun attrEntry(attribute: String): Map<String, String> {
        val description = when (attribute) {
            "is_extends" -> "At least one type in the source subtree extends a type in the target subtree"
            "is_implements" -> "At least one type in the source subtree implements an interface in the target subtree"
            "is_annotated_by" -> "At least one type in the source subtree is annotated by an annotation type in the target subtree"
            "is_depends_on_other" -> "At least one other form of dependency exists (calls, throws, parameter types, field types, etc. — the residual after extends/implements/annotated_by)"
            else -> attribute
        }
        return mapOf("attribute" to attribute, "description" to description)
    }

    private fun detailRelEntry(kind: String, source: String, description: String) =
        mapOf("kind" to kind, "source" to source, "description" to description)
}
