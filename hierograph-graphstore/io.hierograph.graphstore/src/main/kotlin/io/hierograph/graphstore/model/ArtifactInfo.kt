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
package io.hierograph.graphstore.model

/** Where a scanned artifact comes from; drives API-vs-FULL detail and snapshot caching. */
enum class Origin { MODULE_MAIN, MODULE_TEST, LIBRARY, JDK }

/**
 * Identity and provenance of one scanned artifact (module, library jar, or JDK), surfaced as a
 * top-level MODULE node in the GraphStore. [key] is the content-derived snapshot key.
 */
data class ArtifactInfo(
    val key: String,
    val name: String,
    val fqn: String,
    val origin: Origin,
)
