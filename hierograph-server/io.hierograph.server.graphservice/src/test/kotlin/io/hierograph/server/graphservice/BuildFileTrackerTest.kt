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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class BuildFileTrackerTest {

    @Test
    fun `a burst of edits is reported once, after it settles`(@TempDir tmp: Path) {
        val poms = (1..20).map { Files.writeString(tmp.resolve("pom$it.xml"), "<project/>") }
        val tracker = BuildFileTracker(poms.toSet(), settleMs = 100)

        poms.forEachIndexed { i, pom -> touch(pom, "<project><version>$i</version></project>") }
        assertThat(tracker.poll(now = 1_000)).`as`("still within the settle window").isEmpty()
        assertThat(tracker.poll(now = 1_050)).isEmpty()
        assertThat(tracker.poll(now = 1_200)).containsExactlyInAnyOrderElementsOf(poms)
        assertThat(tracker.poll(now = 2_000)).`as`("reported once").isEmpty()
    }

    @Test
    fun `an edit reverted within the burst is not reported`(@TempDir tmp: Path) {
        val pom = Files.writeString(tmp.resolve("pom.xml"), "<project/>")
        val mtime = Files.getLastModifiedTime(pom)
        val tracker = BuildFileTracker(setOf(pom), settleMs = 100)

        touch(pom, "<project><x/></project>")
        assertThat(tracker.poll(now = 1_000)).isEmpty()
        Files.writeString(pom, "<project/>")
        Files.setLastModifiedTime(pom, mtime)
        assertThat(tracker.poll(now = 1_010)).isEmpty()
        assertThat(tracker.poll(now = 1_200)).isEmpty()
    }

    private fun touch(file: Path, content: String) {
        val before = Files.getLastModifiedTime(file).toMillis()
        Files.writeString(file, content)
        Files.setLastModifiedTime(file, FileTime.fromMillis(before + 5_000))
    }
}
