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

import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.server.mapping.JavaEdgeAttributes
import io.hierograph.server.mapping.GraphStoreDependencySource
import java.util.EnumMap
import io.hierograph.server.views.KindAliases
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.mcp.server.core.INodeRefFactory
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.mcp.server.tools.views.ViewResponses
import io.hierograph.server.graphservice.views.ViewService
import io.hierograph.server.views.ViewGroups
import org.springframework.ai.tool.annotation.Tool
import org.springframework.stereotype.Component

/**
 * MCP tool: `graph_overview`
 *
 * Returns a structural overview of the loaded codebase: statistics, the kind and
 * relationship vocabularies, top-level module structure, and the zoom-level model.
 * No parameters of its own — it describes the full loaded graph, or, called with `view`, the graph in that view.
 */
@Component
class GraphOverviewTool(
    private val graphService: HierarchicalGraphService,
    private val nodeRefFactory: INodeRefFactory,
    private val viewService: ViewService? = null,
) {

    @Tool(
        name = "graph_overview",
        description = "[Discovery and navigation] " +
                "Return a structural overview of the loaded codebase. This is the orientation " +
                "tool — call it first when starting a new session to learn what's in the " +
                "codebase, what vocabulary the other tools use, and how dependency analysis " +
                "tools relate to each other. Returns statistics (node counts by kind, edge " +
                "counts by attribute), the kind and relationship vocabularies, top-level " +
                "module structure, and the zoom-level model. In manifest mode it also returns the " +
                "build_state ('current', or 'stale' with the build files edited since the graph's manifest was " +
                "written). With view=<name>, the hierarchy is the view's top level — its groups and the nodes no group " +
                "takes — and a 'view' section summarizes the view and what applying it reported. " +
                "No other parameters. For known codebases, this can be skipped."
    )
    fun graphOverview(): Map<String, Any?> {

        val hierarchy = graphService.model.hierarchy
        val rootNode = hierarchy.rootNode
        val view = viewService?.currentView()

        // ── stats: count nodes by kind ─────────────────────────────────
        // traverse visits the descendants of its start node, so starting at the root counts every node
        // except the root itself, modules included.
        val nodesByKind = linkedMapOf<String, Int>()
        var totalNodes = 0
        hierarchy.traverse(rootNode) { node ->
            val kindStr = node.kind?.toString() ?: "unknown"
            nodesByKind.merge(kindStr, 1) { a, b -> a + b }
            totalNodes++
        }

        // ── stats: count type-level edges by attribute ────────────────
        val edgesByAttribute = linkedMapOf<String, Int>()
        for ((_, name) in JavaEdgeAttributes.ALL) {
            edgesByAttribute[name] = 0
        }
        val weightByKind = EnumMap<DependencyKind, Int>(DependencyKind::class.java)
        var totalEdges = 0
        for (child in hierarchy.childrenOf(rootNode)) {
            hierarchy.traverse(child) { node ->
                for (dep in node.outgoingCoreDependencies) {
                    totalEdges++
                    for ((kind, count) in GraphStoreDependencySource.kindsOf(dep)) weightByKind.merge(kind, count, Int::plus)
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
                kindEntry(JavaNodeKind.FIELD, "Field"),
                mapOf(
                    "kind" to JavaNodeKind.EXTERNAL_TYPE,
                    "description" to "Type referenced by the scanned code but not scanned itself; " +
                            "lives under the 'External Types' module and has no members"
                )
            ) + listOfNotNull(
                view?.let {
                    mapOf(
                        "kind" to ViewGroups.KIND,
                        "description" to "Group of the view '${it.entry.name}'; exists only in the view and " +
                                "aggregates the dependencies of the nodes below it"
                    )
                }
            ),
            "group_aliases" to mapOf(
                KindAliases.TYPES to JavaNodeKind.TYPES.map { it.value },
                KindAliases.MEMBERS to JavaNodeKind.MEMBERS.map { it.value },
                KindAliases.PACKAGES to listOf(JavaNodeKind.PACKAGE.value)
            )
        )

        // ── relationships vocabulary ───────────────────────────────────
        val relationships = mapOf(
            "type_level_attributes" to JavaEdgeAttributes.ALL.map { (_, name) ->
                attrEntry(name)
            },
            "type_level_kinds" to DependencyKind.AGGREGATED.map { kind ->
                mapOf("kind" to kind.value, "description" to typeLevelKindDescription(kind))
            },
            "detail_level" to DependencyKind.entries.map { kind ->
                val (source, description) = detailKindDescription(kind)
                linkedMapOf<String, Any>("kind" to kind.value, "source" to source, "description" to description).apply {
                    if (kind.derived) put("derived", true)
                    if (!kind.counted && !kind.derived) put("counted", false)
                }
            },
        )

        // ── hierarchy: top-level modules with enriched metadata ────────
        // The counts are boundary-crossing: a dependency between two types of the same module is internal
        // and counts for neither side.
        val moduleOf = HashMap<Any, Any>()
        for (module in hierarchy.childrenOf(rootNode)) {
            moduleOf[module.identifier] = module.identifier
            hierarchy.traverse(module) { moduleOf[it.identifier] = module.identifier }
        }
        val hierarchySection = hierarchy.childrenOf(rootNode).map { child ->
            val ref = nodeRefFactory.enrichedNodeRef(child)
            ref["outgoing_dep_count"] = hierarchy.accumulatedOutgoing(child).count { moduleOf[it.to.identifier] != child.identifier }
            ref["incoming_dep_count"] = hierarchy.accumulatedIncoming(child).count { moduleOf[it.from.identifier] != child.identifier }
            ref
        }

        // ── model: zoom-level description ──────────────────────────────
        val model = mapOf(
            "aggregation" to "Aggregation is pairwise. Given any two subtrees, aggregated_dependencies " +
                    "computes one aggregated edge between them. Provide sourceIds and targetIds as sets; " +
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
                    "parameter" to "detailLevel=\"type\" (default)"
                ),
                mapOf(
                    "name" to "detail",
                    "description" to "Method/field-level edges between two specific subtrees, queried on demand",
                    "tools" to listOf("outgoing_dependencies", "incoming_dependencies"),
                    "parameter" to "detailLevel=\"detail\""
                )
            )
        )

        // ── scan metadata ──────────────────────────────────────────────
        val scanMetadata = mapOf<String, Any?>(
            "scanner" to "hierograph"
        )

        // ── assemble response ──────────────────────────────────────────
        return linkedMapOf(
            "stats" to linkedMapOf(
                "total_nodes" to totalNodes,
                "nodes_by_kind" to nodesByKind,
                "total_type_level_edges" to totalEdges,
                "type_level_edges_by_attribute" to edgesByAttribute,
                "type_level_weight_by_kind" to DependencyKind.toMap(weightByKind),
            ),
            "kinds" to kinds,
            "relationships" to relationships,
            "hierarchy" to hierarchySection,
            "model" to model,
            "scan_metadata" to scanMetadata
        ).apply {
            view?.let {
                put("view", ViewResponses.entry(it.entry).apply {
                    putAll(ViewResponses.summary(it))
                    with(ViewResponses) { putRestructuring(it) }
                    put("report", ViewResponses.report(it.materialized.report))
                })
            }
            graphService.buildState()?.let { put("build_state", it) }
        }
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

    /** The source and description of a detail-level kind, as graph_overview lists it. */
    private fun detailKindDescription(kind: DependencyKind): Pair<String, String> = when (kind) {
        DependencyKind.EXTENDS -> "type" to "Type has this superclass"
        DependencyKind.IMPLEMENTS -> "type" to "Type implements (or, as an interface, extends) this interface"
        DependencyKind.ANNOTATED_BY -> "type/method/field" to "Entity has this annotation type"
        DependencyKind.PARAMETER_ANNOTATED_BY -> "method" to "Method has a parameter with this annotation type"
        DependencyKind.ANNOTATION_VALUE -> "type/method/field" to "An annotation on the entity uses this type as a value (enum constant, class value, nested annotation)"
        DependencyKind.TYPE_ARGUMENT -> "type/method/field" to "The entity's generic signature names this type in a type argument or bound"
        DependencyKind.HAS_TYPE -> "field" to "Field is of this type"
        DependencyKind.PARAMETER_TYPE -> "method" to "Method has a parameter of this type"
        DependencyKind.RETURNS -> "method" to "Method's return type"
        DependencyKind.THROWS -> "method" to "Method declares it throws this exception type"
        DependencyKind.CALLS -> "method" to "Method invokes a method"
        DependencyKind.READS_FIELD -> "method" to "Method reads a field"
        DependencyKind.WRITES_FIELD -> "method" to "Method writes a field"
        DependencyKind.INSTANTIATES -> "method" to "Method creates an instance or an array of this type"
        DependencyKind.CASTS -> "method" to "Method casts to, or tests instanceof against, this type"
        DependencyKind.CATCHES -> "method" to "Method catches this exception type"
        DependencyKind.CLASS_LITERAL -> "method" to "Method uses this type as a class literal (X.class)"
        DependencyKind.RAISES -> "method" to "Method body constructs and throws this exception type (throw new X(...)); not counted in weights"
        DependencyKind.OVERRIDES -> "method" to "Method overrides another method (derived at load)"
        DependencyKind.READ_BY -> "field" to "Field is read by this method (reads_field viewed from the field)"
        DependencyKind.WRITTEN_BY -> "field" to "Field is written by this method (writes_field viewed from the field)"
    }

    /** The description of a type-level kind: the facts its count is made of. */
    private fun typeLevelKindDescription(kind: DependencyKind): String = "${kind.value} facts of the type and its members"
}
