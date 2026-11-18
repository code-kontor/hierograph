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

/** The server serves a built web UI at `/ui/` (here a small fixture in place of the real build). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebUiTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) =
            WebUiTestSupport.serverProperties(registry, "classpath:/web-ui-fixture/")
    }

    @LocalServerPort
    private var port: Int = 0

    private fun get(path: String) = WebUiTestSupport.get(port, path)

    @Test
    fun `ui root serves index html, not cached`() {
        val response = get("/ui/")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("web-ui-fixture index")
        assertThat(response.header("Content-Type")).startsWith("text/html")
        assertThat(response.header("Cache-Control")).isEqualTo("no-cache")
    }

    @Test
    fun `a deep link to an app route serves index html`() {
        val response = get("/ui/dsm/some/state?subject_ids=42,43")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("web-ui-fixture index")
    }

    @Test
    fun `hashed assets are cached for a year`() {
        val response = get("/ui/assets/app-abc123.js")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("web-ui-fixture asset")
        assertThat(response.header("Cache-Control")).contains("max-age=31536000").contains("immutable")
    }

    @Test
    fun `other files of the ui are served, not cached`() {
        val response = get("/ui/favicon.svg")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.header("Content-Type")).startsWith("image/svg+xml")
        assertThat(response.header("Cache-Control")).isEqualTo("no-cache")
    }

    @Test
    fun `a missing file is a 404, not the app`() {
        assertThat(get("/ui/assets/missing-123.js").statusCode()).isEqualTo(404)
        assertThat(get("/ui/missing.js").statusCode()).isEqualTo(404)
    }

    @Test
    fun `root and ui without slash redirect to the ui`() {
        listOf("/", "/ui").forEach { path ->
            val response = get(path)

            assertThat(response.statusCode()).describedAs(path).isEqualTo(302)
            assertThat(response.header("Location")).describedAs(path).endsWith("/ui/")
        }
    }

    @Test
    fun `graphql is not taken over by the ui`() {
        val response = WebUiTestSupport.postJson(port, "/graphql", """{"query":"{ __typename }"}""")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"__typename\":\"Query\"")
    }

    @Test
    fun `actuator is not taken over by the ui`() {
        val response = get("/actuator/info")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).doesNotContain("web-ui-fixture")
    }

    @Test
    fun `the mcp endpoint is not taken over by the ui`() {
        assertThat(get("/mcp").body()).doesNotContain("web-ui-fixture")
    }
}
