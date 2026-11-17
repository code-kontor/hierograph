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
package io.hierograph.server.views

/**
 * A pattern over qualified names (views specification, §4.1), matched against the whole name:
 *
 * | Form | Matches |
 * |------|---------|
 * | `*` | any run of characters except `:` |
 * | `**` | any run of characters, `:` included |
 * | `{a,b}` | one of the alternatives; an alternative may contain `*` and `**`, but no further braces |
 * | anything else | itself |
 *
 * `*` stops at `:` so that a pattern over module coordinates (`group:artifact:version`) matches one
 * coordinate per `*`: `io.hierograph:io.hierograph.server:*` matches the main artifact, not the test
 * artifact `…:0.2.0-SNAPSHOT:test`.
 */
class NamePattern private constructor(
    /** The pattern as written. */
    val source: String,
    private val regex: Regex?,
) {
    /** True when the pattern has no wildcards or alternatives and matches only [source] itself. */
    val isExact: Boolean get() = regex == null

    fun matches(qualifiedName: String): Boolean =
        regex?.matches(qualifiedName) ?: (qualifiedName == source)

    override fun toString(): String = source

    override fun equals(other: Any?): Boolean = other is NamePattern && other.source == source

    override fun hashCode(): Int = source.hashCode()

    companion object {

        private const val REGEX_METACHARACTERS = "\\.[]()^$|?+"

        /** Compiles [source]; fails with [IllegalArgumentException] naming the problem and its position. */
        fun compile(source: String): NamePattern {
            require(source.isNotEmpty()) { "A pattern must not be empty" }
            if (source.none { it == '*' || it == '{' || it == '}' }) return NamePattern(source, null)

            val regex = StringBuilder()
            var openBrace = -1
            var i = 0
            while (i < source.length) {
                when (val c = source[i]) {
                    '*' -> if (i + 1 < source.length && source[i + 1] == '*') {
                        regex.append(".*")
                        i++
                    } else {
                        regex.append("[^:]*")
                    }
                    '{' -> {
                        require(openBrace < 0) { "Nested '{' at position ${i + 1} in '$source'" }
                        openBrace = i
                        regex.append("(?:")
                    }
                    '}' -> {
                        require(openBrace >= 0) { "'}' without '{' at position ${i + 1} in '$source'" }
                        openBrace = -1
                        regex.append(')')
                    }
                    ',' -> regex.append(if (openBrace >= 0) "|" else ",")
                    else -> {
                        if (c in REGEX_METACHARACTERS) regex.append('\\')
                        regex.append(c)
                    }
                }
                i++
            }
            require(openBrace < 0) { "'{' at position ${openBrace + 1} is not closed in '$source'" }
            return NamePattern(source, Regex(regex.toString()))
        }
    }
}
