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
package io.hierograph.mcp.server.core.pagination

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64

/**
 * Serializes [Cursor] values to/from their opaque on-the-wire form.
 *
 * Wire format: the cursor's JSON object (with the compact field names `v`, `tool`, `qh`, `dh`,
 * `offset`), UTF-8 encoded, then base64-URL encoded without padding. The result is roughly 200 bytes
 * — small enough for any transport, and URL-safe so it survives any encoding the MCP layer applies.
 *
 * The codec is intentionally narrow: it only deals with the conversion between a [Cursor] and its
 * string form. It does not judge whether a successfully-decoded cursor is *usable* — version support,
 * tool match, query-hash match, and data-hash match are the validation layer's concern. The one
 * failure the codec itself reports is [CursorFormatException] (`INVALID_CURSOR_FORMAT`), raised when
 * the input cannot be turned back into a structurally valid [Cursor] at all.
 */
object CursorCodec {

    private val mapper = jacksonObjectMapper()

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /**
     * Encodes [cursor] into its opaque, base64-URL string form.
     */
    fun encode(cursor: Cursor): String {
        val json = mapper.writeValueAsBytes(cursor)
        return encoder.encodeToString(json)
    }

    /**
     * Decodes an opaque cursor string back into a [Cursor].
     *
     * @throws CursorFormatException if [raw] is not valid base64-URL, its decoded content is not valid
     *   JSON, a required field is missing or has the wrong type, or a field carries a structurally
     *   invalid value (a negative offset).
     */
    fun decode(raw: String): Cursor {
        val bytes = try {
            decoder.decode(raw)
        } catch (e: IllegalArgumentException) {
            throw CursorFormatException("The cursor is not valid base64.", e)
        }

        val root = try {
            mapper.readTree(bytes)
        } catch (e: Exception) {
            throw CursorFormatException("The cursor content is not valid JSON.", e)
        }
        if (root == null || !root.isObject) {
            throw CursorFormatException("The cursor content is not a JSON object.")
        }

        // Explicit presence/type checks: don't rely on the deserializer's missing-field semantics,
        // which silently default a missing numeric field to zero. A missing or mistyped field here
        // means the cursor is malformed.
        val offset = root.requireInt("offset")
        if (offset < 0) {
            throw CursorFormatException("The cursor offset must not be negative, but was $offset.")
        }

        return Cursor(
            version = root.requireInt("v"),
            tool = root.requireText("tool"),
            queryHash = root.requireText("qh"),
            dataHash = root.requireText("dh"),
            offset = offset
        )
    }

    private fun JsonNode.requireInt(field: String): Int {
        val node = get(field) ?: throw CursorFormatException("The cursor is missing the '$field' field.")
        if (!node.isInt && !node.isLong) {
            throw CursorFormatException("The cursor field '$field' is not an integer.")
        }
        return node.asInt()
    }

    private fun JsonNode.requireText(field: String): String {
        val node = get(field) ?: throw CursorFormatException("The cursor is missing the '$field' field.")
        if (!node.isTextual) {
            throw CursorFormatException("The cursor field '$field' is not a string.")
        }
        return node.asText()
    }
}
