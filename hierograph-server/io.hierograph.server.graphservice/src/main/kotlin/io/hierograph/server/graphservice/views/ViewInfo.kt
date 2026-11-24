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
package io.hierograph.server.graphservice.views

import io.hierograph.hierarchicalgraph.model.NodeMetadata

/**
 * A view as listed: its registry [entry] and, when it could be applied to the graph in service, a summary of
 * the result ([applied]). `list_views` (MCP) and `views` (GraphQL) both list these.
 */
class ViewInfo(val entry: ViewEntry, val applied: AppliedViewSummary?) {
    val name: String get() = entry.name
    val description: String? get() = entry.document?.description
    /** Where the document lives: `project` (kept with the code) or `state` (defined at runtime). */
    val source: String get() = entry.source.name.lowercase()
    val file: String get() = entry.file.toString()
    val shadowsProjectView: Boolean get() = entry.shadowsProjectView
    val valid: Boolean get() = entry.isValid
    /** Why the document cannot be applied; empty for a valid one. */
    val problems: List<String> get() = entry.problems
    val groupCount: Int? get() = applied?.groupCount
    val errors: Int? get() = applied?.errors
    val warnings: Int? get() = applied?.warnings
    val restructure: Boolean get() = applied?.restructure ?: (entry.document?.restructure == true)
    /** The nodes the view leaves out, with everything inside them; `null` without exclusions or when not applied. */
    val excludedCount: Int? get() = applied?.excludedCount
    /** The outermost nodes the view leaves out; `null` without exclusions or when not applied. */
    val excludedNodes: List<ExcludedNode>? get() = applied?.excluded
}

/** A node a view leaves out, with everything inside it: its id, qualified name and kind, as a node names them. */
data class ExcludedNode(val id: String, val text: String, val type: String)

/**
 * The hierarchy as scanned: what a call without `view` answers in. Listed beside the views rather than among
 * them, so no view name is reserved for it. [topLevelCount] is `null` while no graph is loaded.
 */
class DefaultViewInfo(val topLevelCount: Int?) {
    val description: String get() = DESCRIPTION

    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>("description" to description).apply {
        topLevelCount?.let { put("top_level_count", it) }
    }

    companion object {
        const val DESCRIPTION = "The hierarchy as scanned. Omit view to use it."
    }
}

/**
 * What applying a view to a graph produced: how many groups, errors and warnings, for a view with exclusions
 * how many nodes it leaves out, and for a restructuring its size and effect.
 */
data class AppliedViewSummary(
    val groupCount: Int,
    val errors: Int,
    val warnings: Int,
    val restructure: Boolean,
    val virtualModuleCount: Int?,
    val introducesCycles: Boolean?,
    /** The nodes the view leaves out, with everything inside them; `null` for a view without exclusions. */
    val excludedCount: Int? = null,
    /** The outermost nodes the view leaves out; `null` for a view without exclusions. Not in [toMap]. */
    val excluded: List<ExcludedNode>? = null,
) {
    fun toMap(): LinkedHashMap<String, Any?> = linkedMapOf<String, Any?>(
        "group_count" to groupCount,
        "errors" to errors,
        "warnings" to warnings,
    ).apply {
        excludedCount?.let { put("excluded_count", it) }
        if (restructure) {
            put("restructure", true)
            put("virtual_module_count", virtualModuleCount)
            introducesCycles?.let { put("introduces_cycles", it) }
        }
    }

    companion object {
        fun of(view: ViewSnapshot): AppliedViewSummary {
            val materialized = view.materialized
            val restructure = materialized.document.restructure
            val excludes = materialized.document.exclude.isNotEmpty()
            return AppliedViewSummary(
                groupCount = materialized.groupNodes.size,
                errors = materialized.report.errors.size,
                warnings = materialized.report.warnings.size,
                restructure = restructure,
                virtualModuleCount = if (restructure) materialized.virtualModules.size else null,
                introducesCycles = if (restructure) materialized.effect?.let { it.newCycles.isNotEmpty() } else null,
                excludedCount = if (excludes) materialized.excludedCount else null,
                excluded = if (excludes) {
                    materialized.excluded.map {
                        ExcludedNode(it.identifier.toString(), NodeMetadata.getQualifiedName(it), it.kind?.toString() ?: "Unknown")
                    }
                } else {
                    null
                },
            )
        }
    }
}
