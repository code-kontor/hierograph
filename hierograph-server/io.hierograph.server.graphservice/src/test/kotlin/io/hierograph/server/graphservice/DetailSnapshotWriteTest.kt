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

import io.hierograph.graphstore.jvm.archive.model.Dependency
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** The early checks of a detail snapshot write: a newer build arriving at each step stops it there. */
class DetailSnapshotWriteTest {

    @TempDir
    lateinit var base: Path

    private val genDir by lazy { base.resolve("gen-0001") }

    /**
     * Records the steps taken; [newerBuildAfter] names the step after which a newer build has arrived. The
     * write writes two artifacts, `a` and `b`, and checks for cancellation before each, as the Parquet writer
     * does.
     */
    private inner class Steps(private val newerBuildAfter: String? = null) {
        val taken = mutableListOf<String>()
        var published: List<Dependency>? = null
        private var newerBuild = newerBuildAfter == "start"

        fun run() = DetailSnapshotWrite.run(
            genDir,
            superseded = { newerBuild },
            write = { dir, cancelled ->
                Files.createDirectories(dir)
                for (artifact in listOf("a", "b")) {
                    if (cancelled()) throw CancellationException("cancelled before $artifact")
                    taken += "write $artifact"
                    Files.writeString(dir.resolve("$artifact.parquet"), "rows")
                    if (newerBuildAfter == "artifact $artifact") newerBuild = true
                }
                if (newerBuildAfter == "write") newerBuild = true
            },
            derive = {
                taken += "derive"
                if (newerBuildAfter == "derive") newerBuild = true
                emptyList()
            },
            publish = {
                taken += "publish"
                published = it
            },
        )
    }

    @Test
    fun `the newest build is written, derived and published, and its directory kept`() {
        val steps = Steps()

        assertThat(steps.run()).isEqualTo(DetailWriteOutcome.PUBLISHED)
        assertThat(steps.taken).containsExactly("write a", "write b", "derive", "publish")
        assertThat(steps.published).isNotNull()
        assertThat(genDir.resolve("a.parquet")).exists()
        assertThat(genDir.resolve("b.parquet")).exists()
    }

    @Test
    fun `a build superseded before its write is skipped without writing anything`() {
        val steps = Steps(newerBuildAfter = "start")

        assertThat(steps.run()).isEqualTo(DetailWriteOutcome.SKIPPED)
        assertThat(steps.taken).isEmpty()
        assertThat(genDir).doesNotExist()
    }

    @Test
    fun `a build superseded during its write stops before the next artifact, and its partial directory is deleted`() {
        val steps = Steps(newerBuildAfter = "artifact a")

        assertThat(steps.run()).isEqualTo(DetailWriteOutcome.CANCELLED)
        assertThat(steps.taken).containsExactly("write a")
        assertThat(steps.published).isNull()
        assertThat(genDir).doesNotExist()
    }

    @Test
    fun `a build superseded after its last artifact is neither derived nor published, and its directory is deleted`() {
        val steps = Steps(newerBuildAfter = "write")

        assertThat(steps.run()).isEqualTo(DetailWriteOutcome.DISCARDED)
        assertThat(steps.taken).containsExactly("write a", "write b")
        assertThat(steps.published).isNull()
        assertThat(genDir).doesNotExist()
    }

    @Test
    fun `a build superseded during its derivation is not published, and its directory is deleted`() {
        val steps = Steps(newerBuildAfter = "derive")

        assertThat(steps.run()).isEqualTo(DetailWriteOutcome.DISCARDED)
        assertThat(steps.taken).containsExactly("write a", "write b", "derive")
        assertThat(steps.published).isNull()
        assertThat(genDir).doesNotExist()
    }
}
