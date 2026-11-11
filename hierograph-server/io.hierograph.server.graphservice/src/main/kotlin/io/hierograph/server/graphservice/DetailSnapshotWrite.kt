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

import io.hierograph.graphstore.jvm.model.DetailRef
import java.nio.file.Path

/** What became of one build's detail snapshot; see [DetailSnapshotWrite.run]. */
internal enum class DetailWriteOutcome {
    /** Written, derived and published as the detail source. */
    PUBLISHED,

    /** Not started: a newer build had arrived before the write began. */
    SKIPPED,

    /** Started, then dropped because a newer build arrived; its directory is deleted. */
    DISCARDED,
}

/**
 * The steps of one `manifest`-mode detail snapshot, each taken only while the build is still the newest.
 *
 * [run] checks [superseded][run] before the write, before deriving the detail edges and before
 * publishing, so outdated work stops as early as possible and an outdated generation is never published.
 * The steps themselves are passed in, which lets a test make a newer build arrive at a chosen point.
 */
internal object DetailSnapshotWrite {

    /**
     * Write the snapshot into [genDir], derive its detail edges and publish them, unless [superseded]
     * reports a newer build first. A generation dropped after the write has its [genDir] deleted.
     * Exceptions from the steps propagate to the caller.
     */
    fun run(
        genDir: Path,
        superseded: () -> Boolean,
        write: (Path) -> Unit,
        derive: (Path) -> List<DetailRef>,
        publish: (List<DetailRef>) -> Unit,
    ): DetailWriteOutcome {
        if (superseded()) return DetailWriteOutcome.SKIPPED
        write(genDir)
        val derived = if (superseded()) null else derive(genDir)
        if (derived != null && !superseded()) {
            publish(derived)
            return DetailWriteOutcome.PUBLISHED
        }
        genDir.toFile().deleteRecursively()
        return DetailWriteOutcome.DISCARDED
    }
}
