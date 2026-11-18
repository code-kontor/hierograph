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

import org.springframework.test.context.DynamicPropertyRegistry
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/** Boots the whole server on a random port, without a graph and without touching the working directory. */
internal object WebUiTestSupport {

    private val client: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    fun serverProperties(registry: DynamicPropertyRegistry, uiLocation: String) {
        val stateDir = Files.createTempDirectory("hierograph-web-ui-test")
        registry.add("hierograph.graph.stateDir") { stateDir.toString() }
        registry.add("hierograph.graph.manifest") { stateDir.resolve("no-manifest.json").toString() }
        registry.add("hierograph.server.single-instance") { "false" }
        registry.add("hierograph.server.runtime-file.enabled") { "false" }
        registry.add("hierograph.web.ui-location") { uiLocation }
    }

    fun get(port: Int, path: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(uri(port, path)).GET().build(), HttpResponse.BodyHandlers.ofString())

    fun postJson(port: Int, path: String, json: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(uri(port, path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun uri(port: Int, path: String) = URI.create("http://localhost:$port$path")
}

internal fun HttpResponse<*>.header(name: String): String? = headers().firstValue(name).orElse(null)
