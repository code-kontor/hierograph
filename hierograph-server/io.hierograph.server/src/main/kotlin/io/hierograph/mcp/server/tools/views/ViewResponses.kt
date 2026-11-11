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
package io.hierograph.mcp.server.tools.views

import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.server.graphservice.views.ViewEntry
import io.hierograph.server.graphservice.views.ViewSnapshot
import io.hierograph.server.views.ModuleDependency
import io.hierograph.server.views.RestructuringEffect
import io.hierograph.server.views.ViewReport

/**
 * How views appear in tool results: their registry entry, their applied groups and their report. Shared
 * by the view tools and `graph_overview`, so a view looks the same wherever it is shown.
 */
object ViewResponses {

    /** Findings listed in full; a longer report lists the first ones and says how many there are. */
    const val MAX_FINDINGS = 50

    /** Nodes listed per finding; a finding about more nodes lists the first ones and counts them all. */
    const val MAX_NODES_PER_FINDING = 10

    /** Name, description, source, file and shadowing of [entry]; with its problems if it is invalid. */
    fun entry(entry: ViewEntry): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
        "name" to entry.name,
        "description" to entry.document?.description,
        "source" to entry.source.name.lowercase(),
        "file" to entry.file.toString(),
        "shadows_project_view" to entry.shadowsProjectView,
        "valid" to entry.isValid,
    ).apply { if (!entry.isValid) put("problems", entry.problems) }

    /** Module dependencies listed per kind of change; a longer list is cut and its length given. */
    const val MAX_MODULE_DEPENDENCIES = 100

    /** The virtual modules of a restructuring view: name, id and number of children. */
    fun virtualModules(view: ViewSnapshot): List<Map<String, Any?>> {
        val hierarchy = view.snapshot.model.hierarchy
        return view.materialized.virtualModules.map { (name, node) ->
            linkedMapOf<String, Any?>("name" to name, "id" to node.identifier, "child_count" to hierarchy.childrenOf(node).size)
        }
    }

    /**
     * The module-level effect of a restructuring: module dependencies added, removed and changed compared
     * with the hierarchy as scanned, the module cycles, and which of them are new.
     */
    fun effect(effect: RestructuringEffect): LinkedHashMap<String, Any?> {
        fun <T> capped(name: String, items: List<T>, render: (T) -> Map<String, Any?>) = buildList {
            add(name to items.take(MAX_MODULE_DEPENDENCIES).map(render))
            if (items.size > MAX_MODULE_DEPENDENCIES) add("${name}_count" to items.size)
        }
        val dependency = { d: ModuleDependency -> linkedMapOf<String, Any?>("from" to d.from, "to" to d.to, "weight" to d.weight) }
        return linkedMapOf<String, Any?>().apply {
            putAll(capped("added", effect.added, dependency))
            putAll(capped("removed", effect.removed, dependency))
            putAll(capped("changed", effect.changed) {
                linkedMapOf("from" to it.from, "to" to it.to, "weight_before" to it.before, "weight_after" to it.after)
            })
            put("cycles", effect.cycles)
            put("new_cycles", effect.newCycles)
            put("introduces_cycles", effect.newCycles.isNotEmpty())
        }
    }

    /** The groups [view] applies, in materialization order: path, id, parent id and number of children. */
    fun groups(view: ViewSnapshot): List<Map<String, Any?>> {
        val hierarchy = view.snapshot.model.hierarchy
        return view.materialized.groupNodes.map { (path, node) ->
            linkedMapOf<String, Any?>(
                "path" to path,
                "id" to node.identifier,
                "parent_id" to hierarchy.parentOf(node)?.identifier,
                "child_count" to hierarchy.childrenOf(node).size,
            )
        }
    }

    /** Counts and the findings of [report], each with its nodes as id and qualified name. */
    fun report(report: ViewReport): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
        "errors" to report.errors.size,
        "warnings" to report.warnings.size,
        "infos" to report.infos.size,
        // Errors first: under the cap, a flood of warnings must not hide the error that explains the result.
        "findings" to report.findings.sortedBy { it.severity.ordinal }.take(MAX_FINDINGS).map { finding ->
            linkedMapOf<String, Any?>(
                "severity" to finding.severity.name.lowercase(),
                "code" to finding.code.name,
                "group" to finding.group,
                "message" to finding.message,
                "selector" to finding.selector?.toString(),
                "nodes" to finding.nodes.take(MAX_NODES_PER_FINDING).map {
                    linkedMapOf<String, Any?>("id" to it.identifier, "qualified_name" to NodeMetadata.getQualifiedName(it))
                },
            ).apply { if (finding.nodes.size > MAX_NODES_PER_FINDING) put("node_count", finding.nodes.size) }
        },
    ).apply { if (report.findings.size > MAX_FINDINGS) put("finding_count", report.findings.size) }

    /** Counts only: errors, warnings and applied groups; for a restructuring view also its virtual modules and new cycles. */
    fun summary(view: ViewSnapshot): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
        "group_count" to view.materialized.groupNodes.size,
        "errors" to view.materialized.report.errors.size,
        "warnings" to view.materialized.report.warnings.size,
    ).apply {
        if (view.materialized.document.restructure) {
            put("restructure", true)
            put("virtual_module_count", view.materialized.virtualModules.size)
            view.materialized.effect?.let { put("introduces_cycles", it.newCycles.isNotEmpty()) }
        }
    }

    /** [entry] plus the applied groups and the full report of [view]; for a restructuring view its virtual modules and effect. */
    fun applied(view: ViewSnapshot): LinkedHashMap<String, Any?> = entry(view.entry).apply {
        putRestructuring(view)
        put("groups", groups(view))
        put("report", report(view.materialized.report))
    }

    /** Adds the virtual modules and the module-level effect of a restructuring [view]; nothing for a grouping view. */
    fun LinkedHashMap<String, Any?>.putRestructuring(view: ViewSnapshot) {
        if (!view.materialized.document.restructure) return
        put("virtual_modules", virtualModules(view))
        view.materialized.effect?.let { put("restructuring", effect(it)) }
    }

    fun error(code: String, message: String, recovery: String, vararg extra: Pair<String, Any?>): Map<String, Any?> =
        mapOf("error" to linkedMapOf<String, Any?>("code" to code, "message" to message, "recovery" to recovery, *extra))
}
