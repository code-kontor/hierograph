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

/** Per-module descriptor location, relative to a module's `target` directory (`hierograph:create-descriptor` writes it). */
internal const val DESCRIPTOR_SUBDIR = "hierograph"
internal const val DESCRIPTOR_FILE = "module.json"

/** The merged manifest file name written into the top-level `.hierograph` directory (`hierograph:merge-descriptors`). */
internal const val MANIFEST_FILE = "manifest.json"

/**
 * One classpath entry of a module, in the module's resolved compile+runtime order. [internal] marks a
 * reactor sibling (scanned from its own output directory, so [jar] is irrelevant); [jar] is the resolved
 * local path of an external dependency, which lets the server API-scan libraries it has not yet cached.
 */
internal data class ClasspathEntry(
    val gav: String,
    val internal: Boolean,
    val jar: String?,
)

/**
 * A single reactor module's descriptor — written per module by `hierograph:create-descriptor` and merged
 * into the manifest by `hierograph:merge-descriptors`. Only the build tool knows these facts (module layout
 * + the already-resolved classpaths), so the plugin reports them and the server never has to resolve
 * dependencies itself. [classpath] is the module's non-test scopes; [testClasspath] the full test classpath
 * (all scopes). The output directories are the configured ones and need not exist yet: a watching server
 * scans them once they appear.
 */
internal data class ModuleDescriptor(
    val gav: String,
    val name: String,
    val mainOutputDir: String?,
    val testOutputDir: String?,
    val sourceRoots: List<String>,
    val testSourceRoots: List<String>,
    val classpath: List<ClasspathEntry>,
    val testClasspath: List<ClasspathEntry>? = null,
)

/**
 * A file that defines the reactor (a POM, or a `.mvn` configuration file) and the SHA-256 of its content when the
 * manifest was merged. A watching server compares the hash with the file's current content to tell whether the
 * manifest still reflects the build definition.
 */
internal data class BuildFile(
    val path: String,
    val sha256: String,
)

/** The merged top-level manifest `hierograph:create-descriptor` / `hierograph:merge-descriptors` write and the local MCP server reads. */
internal data class Manifest(
    val schemaVersion: Int,
    val generator: String,
    val generatedAt: String,
    val reactorRoot: String,
    val modules: List<ModuleDescriptor>,
    val buildFiles: List<BuildFile>,
)
