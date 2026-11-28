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

import io.hierograph.server.graphservice.SourceFiles.Location
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SourceFilesTest {

    private fun file(root: Path, path: String): Path =
        root.resolve(path).also { Files.createDirectories(it.parent); Files.writeString(it, "") }

    @Test
    fun `finds the file in the package's directory, relative to workspace, source root and repository`(@TempDir repo: Path) {
        Files.createDirectories(repo.resolve(".git"))
        val workspace = repo.resolve("project")
        val main = workspace.resolve("core/src/main/kotlin")
        file(main, "a/b/Outer.kt")

        val location = SourceFiles.locate(listOf(main), "a.b.Outer\$Inner", "Outer.kt", workspace, SourceFiles.repositoryRoot(workspace))

        assertThat(location).isEqualTo(
            Location(
                workspacePath = "core/src/main/kotlin/a/b/Outer.kt",
                sourceRootPath = "a/b/Outer.kt",
                repositoryPath = "project/core/src/main/kotlin/a/b/Outer.kt",
            ),
        )
    }

    @Test
    fun `looks in every source root, and finds a type in the default package`(@TempDir workspace: Path) {
        val java = workspace.resolve("src/main/java")
        val kotlin = workspace.resolve("src/main/kotlin")
        file(kotlin, "Main.kt")

        val location = SourceFiles.locate(listOf(java, kotlin), "Main", "Main.kt", workspace, null)

        assertThat(location).isEqualTo(Location("src/main/kotlin/Main.kt", "Main.kt", null))
    }

    @Test
    fun `outside the package's directory, takes the root's only file of that name`(@TempDir workspace: Path) {
        val root = workspace.resolve("src")
        file(root, "service/Service.kt") // package io.example.service, the common root package omitted

        val location = SourceFiles.locate(listOf(root), "io.example.service.Service", "Service.kt", workspace, null)

        assertThat(location?.sourceRootPath).isEqualTo("service/Service.kt")
    }

    @Test
    fun `finds nothing when the name is ambiguous outside the package's directory, or missing`(@TempDir workspace: Path) {
        val root = workspace.resolve("src")
        file(root, "x/Util.kt")
        file(root, "y/Util.kt")

        assertThat(SourceFiles.locate(listOf(root), "a.Util", "Util.kt", workspace, null)).isNull()
        assertThat(SourceFiles.locate(listOf(root), "a.Missing", "Missing.kt", workspace, null)).isNull()
        assertThat(SourceFiles.locate(listOf(workspace.resolve("absent")), "a.A", "A.kt", workspace, null)).isNull()
    }

    @Test
    fun `a file outside the workspace has no workspace path`(@TempDir tmp: Path) {
        val root = tmp.resolve("elsewhere/src")
        file(root, "a/A.java")

        val location = SourceFiles.locate(listOf(root), "a.A", "A.java", tmp.resolve("workspace"), null)

        assertThat(location).isEqualTo(Location(null, "a/A.java", null))
    }

    @Test
    fun `the repository root is the nearest directory up holding git`(@TempDir tmp: Path) {
        Files.createDirectories(tmp.resolve("repo/.git"))
        Files.createDirectories(tmp.resolve("repo/a/b"))
        Files.writeString(tmp.resolve("repo/a/.git"), "gitdir: ../.git/worktrees/a")

        assertThat(SourceFiles.repositoryRoot(tmp.resolve("repo/a/b"))).isEqualTo(tmp.resolve("repo/a").toAbsolutePath().normalize())
        assertThat(SourceFiles.repositoryRoot(tmp.resolve("repo"))).isEqualTo(tmp.resolve("repo").toAbsolutePath().normalize())
    }
}
