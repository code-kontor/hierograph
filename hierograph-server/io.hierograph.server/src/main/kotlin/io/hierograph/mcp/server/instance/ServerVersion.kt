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
package io.hierograph.mcp.server.instance

import java.util.Properties

/** The server's version, read before the application context exists (for the MCP handshake's `serverInfo`). */
object ServerVersion {

    /** Where the build writes its build info (spring-boot-maven-plugin, goal `build-info`). */
    private const val BUILD_INFO = "META-INF/build-info.properties"

    /** `build.version` from the build info, else the jar's `Implementation-Version`, else `dev`. */
    fun current(): String =
        ServerVersion::class.java.classLoader.getResourceAsStream(BUILD_INFO)?.use { stream ->
            Properties().apply { load(stream) }.getProperty("build.version")
        } ?: ServerVersion::class.java.`package`?.implementationVersion ?: "dev"
}
