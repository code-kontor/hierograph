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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

class CursorCodecTest {

    private val sample = Cursor(
        version = Cursor.CURRENT_VERSION,
        tool = "list_descendants",
        queryHash = "a7f2c8d1b3e4",
        dataHash = "8f3e2c1a9d0b",
        offset = 200
    )

    @Test
    fun `encode then decode round-trips a cursor`() {
        val decoded = CursorCodec.decode(CursorCodec.encode(sample))
        assertThat(decoded).isEqualTo(sample)
    }

    @Test
    fun `encoded form is base64-url without padding`() {
        val encoded = CursorCodec.encode(sample)
        // URL-safe alphabet only: A-Z a-z 0-9 - _, and never '+', '/', or '=' padding
        assertThat(encoded).matches("[A-Za-z0-9_-]+")
    }

    @Test
    fun `encoded JSON uses the compact field names`() {
        val json = String(Base64.getUrlDecoder().decode(CursorCodec.encode(sample)))
        assertThat(json)
            .contains("\"v\"")
            .contains("\"tool\"")
            .contains("\"qh\"")
            .contains("\"dh\"")
            .contains("\"offset\"")
            .doesNotContain("version")
            .doesNotContain("queryHash")
            .doesNotContain("dataHash")
    }

    @Test
    fun `decode rejects non-base64 input`() {
        assertThatThrownBy { CursorCodec.decode("not valid base64!!!") }
            .isInstanceOf(CursorFormatException::class.java)
    }

    @Test
    fun `decode rejects valid base64 that is not JSON`() {
        val garbage = Base64.getUrlEncoder().withoutPadding().encodeToString("not json".toByteArray())
        assertThatThrownBy { CursorCodec.decode(garbage) }
            .isInstanceOf(CursorFormatException::class.java)
    }

    @Test
    fun `decode rejects JSON missing a required field`() {
        val missingOffset = encodeJson("""{"v":1,"tool":"list_descendants","qh":"a","dh":"b"}""")
        assertThatThrownBy { CursorCodec.decode(missingOffset) }
            .isInstanceOf(CursorFormatException::class.java)
    }

    @Test
    fun `decode rejects a negative offset`() {
        val negative = encodeJson("""{"v":1,"tool":"t","qh":"a","dh":"b","offset":-1}""")
        assertThatThrownBy { CursorCodec.decode(negative) }
            .isInstanceOf(CursorFormatException::class.java)
    }

    @Test
    fun `decode ignores unknown fields for forward compatibility`() {
        val extra = encodeJson("""{"v":1,"tool":"t","qh":"a","dh":"b","offset":0,"future":"x"}""")
        val decoded = CursorCodec.decode(extra)
        assertThat(decoded.offset).isEqualTo(0)
        assertThat(decoded.tool).isEqualTo("t")
    }

    @Test
    fun `decode preserves an unsupported version rather than rejecting it`() {
        // A known-but-old version must still decode, so the validation layer can report
        // STALE_CURSOR_VERSION with the precise version number.
        val oldVersion = encodeJson("""{"v":99,"tool":"t","qh":"a","dh":"b","offset":0}""")
        assertThat(CursorCodec.decode(oldVersion).version).isEqualTo(99)
    }

    private fun encodeJson(json: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
}
