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
package io.hierograph.server.views

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind

/** A view document that could not be parsed: [problems] lists every problem found, each with its location. */
class ViewDocumentException(val problems: List<String>) :
    IllegalArgumentException("Invalid view document: " + problems.joinToString("; "))

/**
 * Parses a view document from JSON (views specification, §3) and checks its form: required fields, types,
 * names, duplicate sibling names, selector patterns and kinds, and for a restructuring view its modules and
 * moves (§6.4). It collects every problem rather than
 * stopping at the first, so one round trip shows everything to fix.
 *
 * Whether the selectors match anything is not checked here; that depends on the graph and is reported by
 * [ViewResolver].
 */
object ViewDocumentParser {

    private val mapper = ObjectMapper()

    private val DOCUMENT_FIELDS = setOf("name", "description", "groups", "restructure", "modules", "moves")
    private val GROUP_FIELDS = setOf("name", "description", "members", "groups")
    private val SELECTOR_FIELDS = setOf("match", "kind", "within")
    private val MODULE_FIELDS = setOf("name", "description")
    private val MOVE_FIELDS = setOf("select", "to")

    /** Kind values a selector may name, besides the aliases of [KindAliases.ALL]. */
    private val SELECTABLE_KINDS: Set<String> =
        JavaNodeKind.entries.map { it.value }.toSet() + JavaNodeKind.EXTERNAL_TYPE

    /** Parses [json]; fails with [ViewDocumentException] listing every problem. */
    fun parse(json: String): ViewDocument {
        val root = try {
            mapper.readTree(json)
        } catch (e: JsonProcessingException) {
            val at = e.location?.let { " at line ${it.lineNr}, column ${it.columnNr}" } ?: ""
            throw ViewDocumentException(listOf("Invalid JSON$at: ${e.originalMessage}"))
        }
        val problems = mutableListOf<String>()
        val document = Reader(problems).document(root)
        if (problems.isNotEmpty() || document == null) throw ViewDocumentException(problems)
        return document
    }

    private class Reader(private val problems: MutableList<String>) {

        private fun problem(path: String, message: String) {
            problems += if (path.isEmpty()) message else "$path: $message"
        }

        fun document(node: JsonNode?): ViewDocument? {
            if (node !is ObjectNode) {
                problem("", "the document must be a JSON object")
                return null
            }
            for (field in node.fieldNames()) {
                if (field !in DOCUMENT_FIELDS) problem(field, "unknown field; allowed are ${DOCUMENT_FIELDS.joinToString()}")
            }
            val name = requiredString(node, "name", "name")
            if (name != null && !ViewDocument.NAME_PATTERN.matches(name)) {
                problem("name", "'$name' is not a valid view name; use lower-case letters, digits, '.', '_' and '-', starting with a letter or digit")
            }
            val description = optionalString(node, "description", "description")
            val groups = groups(node, "")
            val restructure = optionalBoolean(node, "restructure", "restructure") ?: false
            val modules = modules(node)
            val moves = moves(node)
            if (!restructure) {
                if (node.has("modules")) problem("modules", "only a restructuring view has modules; set \"restructure\": true")
                if (node.has("moves")) problem("moves", "only a restructuring view has moves; set \"restructure\": true")
            }
            return name?.let { ViewDocument(it, description, groups, restructure, modules, moves) }
        }

        private fun modules(owner: ObjectNode): List<VirtualModuleDefinition> {
            val array = optionalArray(owner, "modules", "modules") ?: return emptyList()
            val modules = array.mapIndexedNotNull { i, element -> module(element, "modules[$i]") }
            modules.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
                problem("modules", "the module name '$it' is used more than once")
            }
            return modules
        }

        private fun module(node: JsonNode, path: String): VirtualModuleDefinition? {
            if (node !is ObjectNode) {
                problem(path, "a module must be a JSON object")
                return null
            }
            for (field in node.fieldNames()) {
                if (field !in MODULE_FIELDS) problem(child(path, field), "unknown field; allowed are ${MODULE_FIELDS.joinToString()}")
            }
            val name = requiredString(node, "name", child(path, "name"))
            if (name != null && !VirtualModuleDefinition.NAME_PATTERN.matches(name)) {
                problem(child(path, "name"), "'$name' is not a valid module name; use letters, digits, '.', '_' and '-', without ':' or '/'")
            }
            val description = optionalString(node, "description", child(path, "description"))
            return name?.let { VirtualModuleDefinition(it, description) }
        }

        private fun moves(owner: ObjectNode): List<MoveDefinition> {
            val array = optionalArray(owner, "moves", "moves") ?: return emptyList()
            return array.mapIndexedNotNull { i, element -> move(element, "moves[$i]") }
        }

        private fun move(node: JsonNode, path: String): MoveDefinition? {
            if (node !is ObjectNode) {
                problem(path, "a move must be a JSON object")
                return null
            }
            for (field in node.fieldNames()) {
                if (field !in MOVE_FIELDS) problem(child(path, field), "unknown field; allowed are ${MOVE_FIELDS.joinToString()}")
            }
            val selectNode = node.get("select")
            val select = if (selectNode == null || selectNode.isNull) {
                problem(child(path, "select"), "required")
                null
            } else {
                selector(selectNode, child(path, "select"))
            }
            val to = requiredString(node, "to", child(path, "to"))
            var validTarget = to != null
            if (to != null) {
                val module = to.substringBefore(MoveDefinition.PACKAGE_SEPARATOR)
                val pkg = to.substringAfter(MoveDefinition.PACKAGE_SEPARATOR, "")
                if (module.isBlank() || (MoveDefinition.PACKAGE_SEPARATOR in to && pkg.isBlank())) {
                    problem(child(path, "to"), "'$to' is not a target; use a module, a virtual module or <module>/<package>")
                    validTarget = false
                } else if (!pattern(module, child(path, "to"))) {
                    validTarget = false
                } else if (pkg.isNotEmpty() && (pkg.startsWith('.') || pkg.endsWith('.') || ".." in pkg || pkg.any { it in "*{},:/" })) {
                    problem(child(path, "to"), "'$pkg' is not a package name")
                    validTarget = false
                }
            }
            return if (select != null && validTarget) MoveDefinition(select, to!!) else null
        }

        private fun groups(owner: ObjectNode, ownerPath: String): List<GroupDefinition> {
            val path = child(ownerPath, "groups")
            val array = optionalArray(owner, "groups", path) ?: return emptyList()
            val groups = array.mapIndexedNotNull { i, element -> group(element, "$path[$i]") }
            groups.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
                problem(path, "the group name '$it' is used more than once among sibling groups")
            }
            return groups
        }

        private fun group(node: JsonNode, path: String): GroupDefinition? {
            if (node !is ObjectNode) {
                problem(path, "a group must be a JSON object")
                return null
            }
            for (field in node.fieldNames()) {
                if (field !in GROUP_FIELDS) problem(child(path, field), "unknown field; allowed are ${GROUP_FIELDS.joinToString()}")
            }
            val name = requiredString(node, "name", child(path, "name"))
            if (name != null) {
                if (name.isBlank()) problem(child(path, "name"), "a group name must not be blank")
                if (GroupDefinition.PATH_SEPARATOR in name) {
                    problem(child(path, "name"), "a group name must not contain '${GroupDefinition.PATH_SEPARATOR}'")
                }
            }
            val description = optionalString(node, "description", child(path, "description"))
            val membersPath = child(path, "members")
            val members = optionalArray(node, "members", membersPath)
                ?.mapIndexedNotNull { i, element -> selector(element, "$membersPath[$i]") }
                ?: emptyList()
            val groups = groups(node, path)
            return name?.let { GroupDefinition(it, description, members, groups) }
        }

        private fun selector(node: JsonNode, path: String): MemberSelector? {
            if (node.isTextual) {
                return if (pattern(node.textValue(), path)) MemberSelector(node.textValue()) else null
            }
            if (node !is ObjectNode) {
                problem(path, "a member selector must be a string or a JSON object")
                return null
            }
            for (field in node.fieldNames()) {
                if (field !in SELECTOR_FIELDS) problem(child(path, field), "unknown field; allowed are ${SELECTOR_FIELDS.joinToString()}")
            }
            val match = requiredString(node, "match", child(path, "match"))
            val kind = optionalString(node, "kind", child(path, "kind"))
            val within = optionalString(node, "within", child(path, "within"))
            var valid = match != null && pattern(match, child(path, "match"))
            if (within != null && !pattern(within, child(path, "within"))) valid = false
            if (kind != null && kind !in SELECTABLE_KINDS && kind !in KindAliases.ALL) {
                problem(child(path, "kind"), "unknown kind '$kind'; use a kind such as java.package or java.class, or one of ${KindAliases.ALL.joinToString()}")
                valid = false
            }
            return if (valid) MemberSelector(match!!, kind, within) else null
        }

        private fun pattern(source: String, path: String): Boolean =
            try {
                NamePattern.compile(source)
                true
            } catch (e: IllegalArgumentException) {
                problem(path, e.message ?: "invalid pattern")
                false
            }

        private fun requiredString(node: ObjectNode, field: String, path: String): String? {
            val value = node.get(field)
            if (value == null || value.isNull) {
                problem(path, "required")
                return null
            }
            if (!value.isTextual) {
                problem(path, "must be a string")
                return null
            }
            return value.textValue()
        }

        private fun optionalString(node: ObjectNode, field: String, path: String): String? {
            val value = node.get(field)
            if (value == null || value.isNull) return null
            if (!value.isTextual) {
                problem(path, "must be a string")
                return null
            }
            return value.textValue()
        }

        private fun optionalBoolean(node: ObjectNode, field: String, path: String): Boolean? {
            val value = node.get(field)
            if (value == null || value.isNull) return null
            if (!value.isBoolean) {
                problem(path, "must be true or false")
                return null
            }
            return value.booleanValue()
        }

        private fun optionalArray(node: ObjectNode, field: String, path: String): List<JsonNode>? {
            val value = node.get(field)
            if (value == null || value.isNull) return null
            if (!value.isArray) {
                problem(path, "must be an array")
                return null
            }
            return value.toList()
        }

        private fun child(path: String, field: String) = if (path.isEmpty()) field else "$path.$field"
    }
}
