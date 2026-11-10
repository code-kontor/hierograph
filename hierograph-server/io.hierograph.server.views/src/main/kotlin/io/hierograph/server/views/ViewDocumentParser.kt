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
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.JavaNodeKind

/** A view document that could not be parsed: [problems] lists every problem found, each with its location. */
class ViewDocumentException(val problems: List<String>) :
    IllegalArgumentException("Invalid view document: " + problems.joinToString("; "))

/**
 * Parses a view document from JSON (views specification, §3) and checks its form: required fields, types,
 * names, duplicate sibling names, selector patterns and kinds. It collects every problem rather than
 * stopping at the first, so one round trip shows everything to fix.
 *
 * Whether the selectors match anything is not checked here; that depends on the graph and is reported by
 * [ViewResolver].
 */
object ViewDocumentParser {

    private val mapper = ObjectMapper()

    private val DOCUMENT_FIELDS = setOf("name", "description", "groups")
    private val GROUP_FIELDS = setOf("name", "description", "members", "groups")
    private val SELECTOR_FIELDS = setOf("match", "kind", "within")
    private val RESTRUCTURING_FIELDS = setOf("restructure", "modules", "moves")

    /** Kind values a selector may name, besides the aliases of [JavaKinds.ALL_ALIASES]. */
    private val SELECTABLE_KINDS: Set<String> =
        JavaNodeKind.entries.map { it.value }.toSet() + JavaKinds.EXTERNAL_TYPE

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
                when (field) {
                    in DOCUMENT_FIELDS -> Unit
                    in RESTRUCTURING_FIELDS -> problem(field, "restructuring views are not supported yet")
                    else -> problem(field, "unknown field; allowed are ${DOCUMENT_FIELDS.joinToString()}")
                }
            }
            val name = requiredString(node, "name", "name")
            if (name != null && !ViewDocument.NAME_PATTERN.matches(name)) {
                problem("name", "'$name' is not a valid view name; use lower-case letters, digits, '.', '_' and '-', starting with a letter or digit")
            }
            val description = optionalString(node, "description", "description")
            val groups = groups(node, "")
            return name?.let { ViewDocument(it, description, groups) }
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
            if (kind != null && kind !in SELECTABLE_KINDS && kind !in JavaKinds.ALL_ALIASES) {
                problem(child(path, "kind"), "unknown kind '$kind'; use a kind such as java.package or java.class, or one of ${JavaKinds.ALL_ALIASES.joinToString()}")
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
