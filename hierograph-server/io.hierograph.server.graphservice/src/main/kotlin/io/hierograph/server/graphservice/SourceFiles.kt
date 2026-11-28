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

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Where a type's source file is. The class file names only the file (its `SourceFile` attribute); the file is
 * looked up in the source roots of the type's artifact, in the package's directory first. A Kotlin file may sit
 * elsewhere (Kotlin allows omitting the common root package), so failing that, a root's only file of that name
 * is taken. Paths are named with `/`, whatever the platform.
 */
object SourceFiles {

    /**
     * A located source file, relative to the [workspace][workspacePath] (the build root; `null` when the file
     * lies outside it), to its [source root][sourceRootPath], and to the [repository root][repositoryPath]
     * (`null` without a repository around the build root, or when the file lies outside it).
     */
    data class Location(val workspacePath: String?, val sourceRootPath: String, val repositoryPath: String?)

    /**
     * The source file [sourceFile] of the type [fqn] in one of [sourceRoots], named relative to [buildRoot],
     * its source root and [repositoryRoot]; `null` when no root holds it (or more than one file of that name,
     * outside the package's directory).
     */
    fun locate(sourceRoots: List<Path>, fqn: String, sourceFile: String, buildRoot: Path, repositoryRoot: Path?): Location? {
        val packageDir = fqn.substringBeforeLast('.', missingDelimiterValue = "").replace('.', '/')
        val (root, file) = sourceRoots.firstNotNullOfOrNull { root ->
            root.resolve(packageDir).resolve(sourceFile).takeIf { it.isRegularFile() }?.let { root to it }
        } ?: sourceRoots.firstNotNullOfOrNull { root -> onlyFileNamed(root, sourceFile)?.let { root to it } }
        ?: return null
        return Location(
            workspacePath = relative(buildRoot, file),
            sourceRootPath = relative(root, file) ?: return null,
            repositoryPath = repositoryRoot?.let { relative(it, file) },
        )
    }

    /** The nearest directory from [start] up that holds `.git` (a directory, or a file in a worktree). */
    fun repositoryRoot(start: Path): Path? =
        generateSequence(start.toAbsolutePath().normalize()) { it.parent }.firstOrNull { Files.exists(it.resolve(".git")) }

    private fun onlyFileNamed(root: Path, name: String): Path? {
        if (!root.isDirectory()) return null
        val matches = Files.walk(root).use { paths ->
            paths.filter { it.name == name && it.isRegularFile() }.limit(2).toList()
        }
        return matches.singleOrNull()
    }

    private fun relative(base: Path, file: Path): String? {
        val b = base.toAbsolutePath().normalize()
        val f = file.toAbsolutePath().normalize()
        if (!f.startsWith(b)) return null
        return b.relativize(f).joinToString("/")
    }
}
