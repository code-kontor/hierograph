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
package io.hierograph.server.graphservice

/**
 * How a module's test output is told apart from its main output. A manifest declares both for one module; the test
 * output becomes a module of its own, keyed by the main key plus [KEY_SUFFIX] and named `<module> (test)`. The key is
 * also the module node's qualified name, in a fresh scan and in a loaded snapshot alike.
 */
object TestArtifacts {
    /** Appended to a module's key to form the key of its test output. */
    const val KEY_SUFFIX = ":test"

    /** Appended to a module's name to form the name of its test output. */
    const val NAME_SUFFIX = " (test)"

    /** The key of the test output of the module keyed [key]. */
    fun key(key: String) = key + KEY_SUFFIX

    /** The name of the test output of the module named [name]. */
    fun name(name: String) = name + NAME_SUFFIX

    /** True if [key], a module's key or qualified name, is that of a test output. */
    fun isTestKey(key: String?) = key != null && key.endsWith(KEY_SUFFIX)
}
