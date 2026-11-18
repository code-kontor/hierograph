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
package io.hierograph.mcp.server.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Without a built web UI (`-Dhierograph.web.skip`), `/ui/` says so instead of answering 404. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebUiNotBuiltTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) =
            WebUiTestSupport.serverProperties(registry, "classpath:/no-web-ui-here/")
    }

    @LocalServerPort
    private var port: Int = 0

    private fun get(path: String) = WebUiTestSupport.get(port, path)

    @Test
    fun `the ui and its routes answer with the not-built page`() {
        listOf("/ui/", "/ui/dsm").forEach { path ->
            val response = get(path)

            assertThat(response.statusCode()).describedAs(path).isEqualTo(200)
            assertThat(response.header("Content-Type")).describedAs(path).startsWith("text/html")
            assertThat(response.body()).describedAs(path)
                .contains("The web UI is not part of this build")
                .contains("-Dhierograph.web.skip")
        }
    }

    @Test
    fun `files of the missing ui are a 404`() {
        assertThat(get("/ui/assets/index-123.js").statusCode()).isEqualTo(404)
    }
}
