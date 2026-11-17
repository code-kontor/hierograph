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
package io.hierograph.mcp.server.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import io.hierograph.mcp.server.pagination.ContinuationStore
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.views.InvalidViewException
import io.hierograph.server.graphservice.views.UnknownViewException
import io.hierograph.server.graphservice.views.ViewService
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * Wraps a delegate [ToolCallbackProvider], runs every tool call against one pinned graph snapshot
 * ([HierarchicalGraphService.withPinnedSnapshot]), and short-circuits every tool call with the standard
 * [GraphUnavailable] description while no hierarchical graph is loaded (the graph source could not be
 * built — see [HierarchicalGraphService.isGraphAvailable]).
 *
 * Tools whose names are in [toolsAllowedWithoutGraph] are exempt: they must run even without a graph so
 * that a graph can be loaded in the first place (`reload_graph`). Everything else answers with the one modular description.
 *
 * With a [viewService], every tool not in [toolsWithoutView] gains the optional `view` parameter
 * ([ViewParameter]): a call that names a view runs with the view's snapshot pinned instead of the graph's
 * ([ViewService.withView]), and an unknown or invalid view is answered with a `VIEW_NOT_FOUND` or
 * `VIEW_INVALID` error without running the tool.
 *
 * A paginated tool — one with a `cursor` parameter — can be continued with the cursor alone: the arguments of
 * every call that returns a `next_cursor` are remembered under it ([continuations]), `view` included, and a later
 * call that passes only `cursor` (and perhaps `limit`) runs with them. So that such a call passes the MCP layer's
 * schema validation, a paginated tool's schema declares no required parameters; this wrapper checks them
 * instead, on a call without a cursor. Repeating the arguments with the cursor keeps working as before.
 */
class GraphAvailabilityToolCallbackProvider(
    private val delegate: ToolCallbackProvider,
    private val graphService: HierarchicalGraphService,
    private val toolsAllowedWithoutGraph: Set<String>,
    private val viewService: ViewService? = null,
    private val toolsWithoutView: Set<String> = emptySet(),
    private val continuations: ContinuationStore = ContinuationStore(),
) : ToolCallbackProvider {

    private val objectMapper = ObjectMapper()

    override fun getToolCallbacks(): Array<ToolCallback> =
        delegate.toolCallbacks.map { GuardingToolCallback(it) }.toTypedArray()

    private inner class GuardingToolCallback(
        private val delegate: ToolCallback
    ) : ToolCallback {

        private val name = delegate.toolDefinition.name()

        private val takesView = viewService != null && name !in toolsWithoutView

        private val ownSchema = objectMapper.readTree(delegate.toolDefinition.inputSchema()) as? ObjectNode

        /** Whether the tool pages its results: it has a `cursor` parameter. */
        private val paginated = ownSchema?.get("properties")?.has(CURSOR) == true

        /** The parameters the tool requires; for a paginated tool this wrapper checks them, not the schema. */
        private val required: List<String> = (ownSchema?.get("required") as? ArrayNode)?.map { it.asText() }.orEmpty()

        private val definition: ToolDefinition = run {
            val own = if (paginated && required.isNotEmpty()) withoutRequired(delegate.toolDefinition) else delegate.toolDefinition
            if (takesView) ViewParameter.addTo(own) else own
        }

        /** The tool's own argument types; `view` is checked and removed before these are. */
        private val argumentTypes = ArgumentTypes(delegate.toolDefinition.inputSchema())

        override fun getToolDefinition(): ToolDefinition = definition

        override fun call(toolInput: String): String =
            guard(toolInput) { delegate.call(it) }

        override fun call(toolInput: String, toolContext: ToolContext?): String =
            guard(toolInput) { delegate.call(it, toolContext) }

        private inline fun guard(toolInput: String, crossinline invoke: (String) -> String): String {
            if (!graphService.isGraphAvailable && name !in toolsAllowedWithoutGraph) {
                return objectMapper.writeValueAsString(GraphUnavailable.response())
            }
            if (!paginated) return runInView(toolInput, invoke)
            val input = when (val resolved = resolveContinuation(toolInput)) {
                is Resolved.Input -> resolved.value
                is Resolved.Refused -> return resolved.response
            }
            val result = runInView(input, invoke)
            rememberContinuation(input, result)
            return result
        }

        /** Runs [toolInput] — in the view it names, if any — after checking its arguments. */
        private inline fun runInView(toolInput: String, crossinline invoke: (String) -> String): String {
            val split = if (takesView) ViewParameter.split(toolInput) else ViewParameter.Split(null, toolInput)
            split.invalid?.let { value ->
                return error(
                    "INVALID_PARAMETER", "'view' must be a string naming a view, not $value.",
                    "Pass the view's name as a string, e.g. \"view\": \"layers\", or leave 'view' out to use the hierarchy as scanned.",
                )
            }
            // A value of the wrong JSON type would fail converting to the tool's parameter, before the tool runs.
            argumentTypes.check(split.input)?.let { return error("INVALID_PARAMETER", it.message, it.recovery) }
            val viewName = split.view
            // One snapshot per call: a reload during the call cannot mix two graphs into one answer.
            if (viewName == null) return graphService.withPinnedSnapshot { invoke(split.input) }
            val views = viewService!!
            val base = graphService.liveSnapshot()
            val view = try {
                graphService.withPinnedSnapshot(base) { views.view(viewName) }
            } catch (e: UnknownViewException) {
                return error("VIEW_NOT_FOUND", e.message!!, "Call list_views for the views there are, or define_view to define one.")
            } catch (e: InvalidViewException) {
                return error("VIEW_INVALID", e.message!!, "Fix the document and define it again with define_view; get_view shows its problems.")
            }
            return views.withView(view) { invoke(split.input) }
        }

        /**
         * The input to run: [toolInput] itself, or — when it passes only a `cursor` (and perhaps a `limit`) — the
         * arguments remembered for that cursor with them. Refused when a cursor-only call names a cursor whose
         * arguments are unknown, or when a call without a cursor misses a required argument.
         */
        private fun resolveContinuation(toolInput: String): Resolved {
            val tree = runCatching { objectMapper.readTree(toolInput) }.getOrNull() as? ObjectNode
                ?: return Resolved.Input(toolInput) // not an object: for the tool to report
            val cursor = tree.get(CURSOR)?.takeIf { it.isTextual }?.asText()
            if (cursor == null) {
                val missing = required.filter { tree.get(it) == null || tree.get(it).isNull }
                if (missing.isEmpty()) return Resolved.Input(toolInput)
                return Resolved.Refused(
                    error(
                        "INVALID_PARAMETER",
                        "Missing required argument${if (missing.size == 1) "" else "s"}: ${missing.joinToString()}.",
                        "Pass ${missing.joinToString(" and ")}; or, to continue a paged query, pass only its cursor.",
                    ),
                )
            }
            val cursorOnly = tree.fieldNames().asSequence().all { it == CURSOR || it == LIMIT }
            if (!cursorOnly) return Resolved.Input(toolInput)
            val arguments = continuations.recall(name, cursor)
            if (arguments == null) {
                // Nothing to restore: a tool without required arguments can still run the call as given.
                if (required.isEmpty()) return Resolved.Input(toolInput)
                return Resolved.Refused(
                    error(
                        "CURSOR_ARGUMENTS_UNKNOWN",
                        "The server no longer knows the arguments this cursor was issued for: it was restarted, or " +
                            "the cursor is older than ${ContinuationStore.DEFAULT_MAX_AGE.toMinutes()} minutes.",
                        "Pass the cursor together with the original call's arguments, or start again without a cursor.",
                    ),
                )
            }
            arguments.put(CURSOR, cursor)
            // The page size carries over from the call that issued the cursor, unless this call sets its own.
            tree.get(LIMIT)?.let { arguments.set<ObjectNode>(LIMIT, it) }
            return Resolved.Input(objectMapper.writeValueAsString(arguments))
        }

        /** Remembers [input]'s arguments, `limit` included, under the `next_cursor` of [result], if it has one. */
        private fun rememberContinuation(input: String, result: String) {
            if (!result.contains("\"$NEXT_CURSOR\"")) return
            val next = runCatching { objectMapper.readTree(result)?.get(NEXT_CURSOR) }.getOrNull()
                ?.takeIf { it.isTextual }?.asText() ?: return
            val arguments = runCatching { objectMapper.readTree(input) }.getOrNull() as? ObjectNode ?: return
            arguments.remove(CURSOR)
            continuations.remember(name, next, arguments)
        }

        private fun error(code: String, message: String, recovery: String): String =
            objectMapper.writeValueAsString(
                mapOf("error" to linkedMapOf("code" to code, "message" to message, "recovery" to recovery)),
            )
    }

    private sealed interface Resolved {
        data class Input(val value: String) : Resolved
        data class Refused(val response: String) : Resolved
    }

    /** [definition] with its input schema's `required` list removed. */
    private fun withoutRequired(definition: ToolDefinition): ToolDefinition {
        val schema = objectMapper.readTree(definition.inputSchema()) as ObjectNode
        schema.remove("required")
        return ToolDefinition.builder()
            .name(definition.name())
            .description(definition.description())
            .inputSchema(objectMapper.writeValueAsString(schema))
            .build()
    }

    private companion object {
        const val CURSOR = "cursor"
        const val LIMIT = "limit"
        const val NEXT_CURSOR = "next_cursor"
    }
}
