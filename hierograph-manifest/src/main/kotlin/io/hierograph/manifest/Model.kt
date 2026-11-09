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
package io.hierograph.manifest

/** Directory, below the build root, that holds the manifest. */
const val MANIFEST_DIR = ".hierograph"

/** The manifest's file name inside [MANIFEST_DIR]. */
const val MANIFEST_FILE = "manifest.json"

/** Manifest schema version; advisory (consumers gate on fields), bumped on a shape change older consumers cannot read. */
const val SCHEMA_VERSION = 2

/** The tool that produced a manifest; informational for consumers, which never branch on it. */
data class BuildTool(
    val name: String,    // "maven", "gradle"
    val version: String, // the tool version on the build host
)

/**
 * One classpath entry of a module, in classpath order. [internal] marks a module of the same build, scanned from
 * its own output directories; its [jar] is `null`. For an external dependency, [jar] is its resolved local path.
 */
data class ClasspathEntry(
    val gav: String,
    val internal: Boolean,
    val jar: String?,
)

/**
 * A single module's descriptor: what only the build tool knows about it. The output directories are the
 * configured ones and need not exist yet; a source set may compile into several (Java and Kotlin), which together
 * form one artifact. [classpath] is the main classpath, [testClasspath] the full test classpath.
 */
data class ModuleDescriptor(
    val gav: String,
    val name: String,
    val mainOutputDirs: List<String>,
    val testOutputDirs: List<String>,
    val sourceRoots: List<String>,
    val testSourceRoots: List<String>,
    val classpath: List<ClasspathEntry>,
    val testClasspath: List<ClasspathEntry>,
)

/** A file that defines the build, with the SHA-256 of its content when the manifest was merged. */
data class BuildFile(
    val path: String,
    val sha256: String,
)

/**
 * The merged manifest. [regenerate] is the command, as an argument vector run in the build root, that rewrites
 * this manifest; an argument starting with `./` is relative to the build root. `null` when the producer cannot
 * name one.
 */
data class Manifest(
    val schemaVersion: Int,
    val generator: String,
    val generatedAt: String,
    val reactorRoot: String,
    val buildTool: BuildTool?,
    val regenerate: List<String>?,
    val modules: List<ModuleDescriptor>,
    val buildFiles: List<BuildFile>,
)
