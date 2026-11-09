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
package io.hierograph.mcp.server.tools

import io.hierograph.graphstore.jvm.model.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KindFilterTest {

    @Test
    fun `absent and empty filters are no filter`() {
        assertThat(KindFilter.parse(null)).isNull()
        assertThat(KindFilter.parse(emptyList())).isNull()
    }

    @Test
    fun `kinds and aliases expand and mix`() {
        val parsed = KindFilter.parse(listOf("java.interface", "packages")) as KindFilter.Valid
        assertThat(parsed.kinds).containsExactlyInAnyOrder(JavaKinds.INTERFACE, JavaKinds.PACKAGE)
    }

    @Test
    fun `unknown values and pseudo-kinds are invalid, in input order`() {
        val parsed = KindFilter.parse(listOf("classes", "java.class", "java.primitive")) as KindFilter.Invalid
        assertThat(parsed.values).containsExactly("classes", "java.primitive")
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `the error lists the invalid and the valid values and a recovery`() {
        val error = KindFilter.invalidResponse(listOf("classes"))["error"] as Map<String, Any?>
        assertThat(error.keys).containsExactly(
            "code", "message", "invalid_values", "valid_kinds", "valid_aliases", "recovery"
        )
        assertThat(error["code"]).isEqualTo("INVALID_KIND")
        assertThat(error["message"] as String).startsWith("Unknown kind: 'classes'. Valid kinds: java.module, ")
        assertThat(error["valid_aliases"]).isEqualTo(listOf("types", "members", "packages"))
    }
}
