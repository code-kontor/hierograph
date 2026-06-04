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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CursorValidatorTest {

    private val tool = "list_descendants"
    private val queryHash = "a7f2c8d1b3e4"
    private val dataHash = "8f3e2c1a9d0b"

    private fun cursor(
        version: Int = Cursor.CURRENT_VERSION,
        tool: String = this.tool,
        queryHash: String = this.queryHash,
        dataHash: String = this.dataHash,
        offset: Int = 150
    ) = Cursor(version, tool, queryHash, dataHash, offset)

    private fun validate(c: Cursor) = CursorValidator.validate(c, tool, queryHash, dataHash)

    @Test
    fun `a matching cursor is valid`() {
        assertThat(validate(cursor())).isNull()
    }

    @Test
    fun `an unsupported version yields StaleVersion`() {
        val error = validate(cursor(version = 99))
        assertThat(error).isInstanceOf(CursorError.StaleVersion::class.java)
        error as CursorError.StaleVersion
        assertThat(error.cursorVersion).isEqualTo(99)
        assertThat(error.supportedVersions).isEqualTo(CursorValidator.SUPPORTED_VERSIONS)
    }

    @Test
    fun `a cursor from another tool yields WrongTool`() {
        val error = validate(cursor(tool = "outgoing_dependencies"))
        assertThat(error).isInstanceOf(CursorError.WrongTool::class.java)
        error as CursorError.WrongTool
        assertThat(error.issuedBy).isEqualTo("outgoing_dependencies")
        assertThat(error.calledOn).isEqualTo(tool)
    }

    @Test
    fun `a changed query hash yields StaleQuery`() {
        assertThat(validate(cursor(queryHash = "different")))
            .isEqualTo(CursorError.StaleQuery)
    }

    @Test
    fun `a changed data hash yields StaleData`() {
        assertThat(validate(cursor(dataHash = "different")))
            .isEqualTo(CursorError.StaleData)
    }

    @Test
    fun `checks are ordered version before tool before query before data`() {
        // Everything is wrong at once; the most fundamental mismatch (version) wins.
        val allWrong = cursor(version = 99, tool = "other", queryHash = "x", dataHash = "y")
        assertThat(validate(allWrong)).isInstanceOf(CursorError.StaleVersion::class.java)

        // With version fixed, tool is next.
        val toolWrong = cursor(tool = "other", queryHash = "x", dataHash = "y")
        assertThat(validate(toolWrong)).isInstanceOf(CursorError.WrongTool::class.java)

        // With version and tool fixed, query is next.
        val queryWrong = cursor(queryHash = "x", dataHash = "y")
        assertThat(validate(queryWrong)).isEqualTo(CursorError.StaleQuery)
    }

    @Test
    fun `StaleVersion renders the spec response shape`() {
        @Suppress("UNCHECKED_CAST")
        val error = CursorError.StaleVersion(1, listOf(2, 3)).toResponse()["error"] as Map<String, Any?>
        assertThat(error["code"]).isEqualTo("STALE_CURSOR_VERSION")
        assertThat(error["cursor_version"]).isEqualTo(1)
        assertThat(error["supported_versions"]).isEqualTo(listOf(2, 3))
        assertThat(error["recovery"] as String).isNotBlank()
        assertThat(error["message"] as String).contains("version 1")
    }

    @Test
    fun `WrongTool renders the spec response shape`() {
        @Suppress("UNCHECKED_CAST")
        val error = CursorError.WrongTool("list_descendants", "outgoing_dependencies")
            .toResponse()["error"] as Map<String, Any?>
        assertThat(error["code"]).isEqualTo("WRONG_TOOL_CURSOR")
        assertThat(error["issued_by"]).isEqualTo("list_descendants")
        assertThat(error["called_on"]).isEqualTo("outgoing_dependencies")
        assertThat(error["message"] as String)
            .contains("'list_descendants'")
            .contains("'outgoing_dependencies'")
    }

    @Test
    fun `InvalidFormat renders the spec response shape`() {
        @Suppress("UNCHECKED_CAST")
        val error = CursorError.InvalidFormat.toResponse()["error"] as Map<String, Any?>
        assertThat(error["code"]).isEqualTo("INVALID_CURSOR_FORMAT")
        assertThat(error["message"] as String).isNotBlank()
        assertThat(error["recovery"] as String).contains("without a cursor")
    }
}
