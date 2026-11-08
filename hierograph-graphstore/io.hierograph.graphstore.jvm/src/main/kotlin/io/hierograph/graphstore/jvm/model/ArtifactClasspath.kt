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
package io.hierograph.graphstore.jvm.model

/**
 * An artifact's ordered classpath, as captured by the Maven plugin and persisted in a snapshot's
 * `classpath.parquet`. [entries] are the dependencies in resolution order; the owning artifact itself is
 * index 0 at load (the loader prepends it), so [entries] holds only the dependencies here.
 */
data class ArtifactClasspath(val artifactKey: String, val name: String, val entries: List<ClasspathEntry>)

/** One classpath entry: the GAV of a dependency and whether it is a scanned reactor sibling. */
data class ClasspathEntry(val depKey: String, val internal: Boolean)
