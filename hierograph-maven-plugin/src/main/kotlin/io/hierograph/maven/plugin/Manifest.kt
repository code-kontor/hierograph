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
package io.hierograph.maven.plugin

/** Current manifest schema version; bump on any incompatible shape change. */
internal const val SCHEMA_VERSION = 1

/** Per-module fragment location, relative to a module's `target` directory (`hierograph:register` writes it). */
internal const val FRAGMENT_SUBDIR = "hierograph"
internal const val FRAGMENT_FILE = "module.json"

/** The merged manifest file name written into the top-level `.hierograph` directory (`hierograph:manifest`). */
internal const val MANIFEST_FILE = "manifest.json"

/**
 * One classpath entry of a module, in the module's resolved compile+runtime order. [internal] marks a
 * reactor sibling (scanned from its own output directory, so [jar] is irrelevant); [jar] is the resolved
 * local path of an external dependency, which lets the server API-scan libraries it has not yet cached.
 */
internal data class ClasspathEntryManifest(
    val gav: String,
    val internal: Boolean,
    val jar: String?,
)

/**
 * A single reactor module — the fragment `hierograph:register` writes per module and `hierograph:manifest`
 * merges. Only the build tool knows these facts (module layout + the already-resolved classpath), so the
 * plugin reports them and the server never has to resolve dependencies itself.
 */
internal data class ModuleManifest(
    val gav: String,
    val name: String,
    val mainOutputDir: String?,
    val testOutputDir: String?,
    val sourceRoots: List<String>,
    val classpath: List<ClasspathEntryManifest>,
)

/** The merged top-level manifest `hierograph:manifest` writes and the local MCP server reads. */
internal data class Manifest(
    val schemaVersion: Int,
    val generator: String,
    val generatedAt: String,
    val reactorRoot: String,
    val modules: List<ModuleManifest>,
)
