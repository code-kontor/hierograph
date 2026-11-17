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
package io.hierograph.mcp.server.pagination

import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Duration

/**
 * The arguments of paged queries, remembered under the cursors issued for them, so that a continuation can pass
 * the cursor alone instead of repeating every argument.
 *
 * A cursor itself stays stateless — it carries only a hash of the arguments ([Cursor.queryHash]) — so repeating
 * the arguments keeps working when the server has forgotten them. The store only adds the shortcut, and is
 * bounded twice: by [capacity] entries (the least recently used goes first) and by [maxAge] since the cursor was
 * issued. It lives in memory and does not survive a restart.
 *
 * Thread-safe.
 */
class ContinuationStore(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxAge: Duration = DEFAULT_MAX_AGE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Entry(val arguments: ObjectNode, val issuedAt: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > capacity
    }

    /** Remembers [arguments] (without `cursor`) as those of the query [cursor] continues on [tool]. */
    @Synchronized
    fun remember(tool: String, cursor: String, arguments: ObjectNode) {
        entries[key(tool, cursor)] = Entry(arguments.deepCopy(), clock())
    }

    /** The arguments remembered for [cursor] on [tool], or `null` when unknown or older than [maxAge]. */
    @Synchronized
    fun recall(tool: String, cursor: String): ObjectNode? {
        val key = key(tool, cursor)
        val entry = entries[key] ?: return null
        if (clock() - entry.issuedAt > maxAge.toMillis()) {
            entries.remove(key)
            return null
        }
        return entry.arguments.deepCopy()
    }

    private fun key(tool: String, cursor: String) = "$tool\u0000$cursor"

    companion object {
        const val DEFAULT_CAPACITY = 512
        val DEFAULT_MAX_AGE: Duration = Duration.ofMinutes(30)
    }
}
