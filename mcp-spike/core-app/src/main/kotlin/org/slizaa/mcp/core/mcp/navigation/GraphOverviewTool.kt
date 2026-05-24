package org.slizaa.mcp.core.mcp.navigation

import org.slizaa.hierarchicalgraph.core.model.HGNodeTraverser
import org.slizaa.mcp.core.HierarchicalGraphService
import org.slizaa.mcp.core.mcp.INodeRefFactory
import org.slizaa.mcp.javaspec.JavaKinds
import org.slizaa.mcp.javaspec.JavaNodeKind
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.INodeMetadataProvider
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
                "counts), the kind and relationship vocabularies, top-level module structure, " +
                "and the zoom-level model. No parameters needed. For known codebases, this " +
                "can be skipped."
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

        // ── stats: count type-level edges by kind ──────────────────────
        val edgesByKind = linkedMapOf<String, Int>()
        var totalEdges = 0
        for (child in rootNode.children) {
            HGNodeTraverser.traverse(child) { node ->
                for (dep in node.outgoingCoreDependencies) {
                    val edgeKind = dep.type ?: "unknown"
                    edgesByKind.merge(edgeKind, 1) { a, b -> a + b }
                    totalEdges++
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
            "type_level" to listOf(
                relEntry("depends_on", "Generic dependency (always present when any detail-level dependency exists)"),
                relEntry("extends", "Source type extends target type"),
                relEntry("implements", "Source type implements target interface"),
                relEntry("annotated_by", "Source type is annotated by target annotation type")
            ),
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
                    "description" to "Pairwise rollup of dependencies between subtrees, with weight and kinds",
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
        val scanMetadata = mapOf(
            "scanner" to mp?.scannerName
        )

        // ── assemble response ──────────────────────────────────────────
        return linkedMapOf(
            "stats" to linkedMapOf(
                "total_nodes" to totalNodes,
                "nodes_by_kind" to nodesByKind,
                "total_type_level_edges" to totalEdges,
                "type_level_edges_by_kind" to edgesByKind
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

    private fun relEntry(kind: String, description: String) =
        mapOf("kind" to kind, "description" to description)

    private fun detailRelEntry(kind: String, source: String, description: String) =
        mapOf("kind" to kind, "source" to source, "description" to description)
}
