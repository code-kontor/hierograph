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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The runtime file `<state dir>/server.json`: where a running server says who it is. The server writes it once
 * it is ready to serve and deletes it on shutdown, so a client, a skill or a second server can find the URL of
 * the server for a workspace without guessing a port.
 *
 * The content is the server's [identity][ServerInstance.identity]: url, port, pid, version, commit, workspace,
 * state directory, graph source and start time.
 */
class RuntimeFile(val path: Path) {

    companion object {
        const val NAME = "server.json"

        /** The runtime file in [stateDir]. */
        fun inStateDir(stateDir: Path): RuntimeFile = RuntimeFile(stateDir.resolve(NAME))

        private val mapper = ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
    }

    /** The recorded content, or `null` when the file is missing or cannot be read as a JSON object. */
    fun read(): Map<String, Any?>? {
        if (!Files.isRegularFile(path)) return null
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            mapper.readValue(path.toFile(), Map::class.java) as Map<String, Any?>
        }.getOrNull()
    }

    /** Writes [content], replacing the file in one step so that a reader never sees a partial file. */
    fun write(content: Map<String, Any?>) {
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, ".$NAME", ".tmp")
        try {
            mapper.writeValue(temp.toFile(), content)
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * Deletes the file if it still names [pid]. A file that another server wrote meanwhile stays. Returns
     * whether the file was deleted.
     */
    fun deleteIfOwnedBy(pid: Long): Boolean {
        val recorded = (read()?.get("pid") as? Number)?.toLong() ?: return false
        return recorded == pid && Files.deleteIfExists(path)
    }
}
