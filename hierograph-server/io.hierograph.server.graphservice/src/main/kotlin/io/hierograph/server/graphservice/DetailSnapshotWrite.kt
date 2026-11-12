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

import io.hierograph.graphstore.jvm.model.Dependency
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** What became of one build's detail snapshot; see [DetailSnapshotWrite.run]. */
internal enum class DetailWriteOutcome {
    /** Written, derived and published as the detail source. */
    PUBLISHED,

    /** Not started: a newer build had arrived before the write began. */
    SKIPPED,

    /** Stopped during the write because a newer build arrived; its partial directory is deleted. */
    CANCELLED,

    /** Written, then dropped because a newer build arrived; its directory is deleted. */
    DISCARDED,
}

/**
 * The steps of one `manifest`-mode detail snapshot, each taken only while the build is still the newest.
 *
 * [run] checks [superseded][run] before the write, during it (the write receives the check and stops
 * between artifacts), before deriving the detail edges and before publishing, so outdated work stops as
 * early as possible and an outdated generation is never published.
 * The steps themselves are passed in, which lets a test make a newer build arrive at a chosen point.
 */
internal object DetailSnapshotWrite {

    /**
     * Write the snapshot into [genDir], derive its detail edges and publish them, unless [superseded]
     * reports a newer build first. [write] receives [superseded] and stops with a [CancellationException]
     * once it reports `true`. A generation dropped during or after the write has its [genDir] deleted.
     * Other exceptions from the steps propagate to the caller.
     */
    fun run(
        genDir: Path,
        superseded: () -> Boolean,
        write: (dir: Path, cancelled: () -> Boolean) -> Unit,
        derive: (Path) -> List<Dependency>,
        publish: (List<Dependency>) -> Unit,
    ): DetailWriteOutcome {
        if (superseded()) return DetailWriteOutcome.SKIPPED
        try {
            write(genDir, superseded)
        } catch (_: CancellationException) {
            genDir.toFile().deleteRecursively()
            return DetailWriteOutcome.CANCELLED
        }
        val derived = if (superseded()) null else derive(genDir)
        if (derived != null && !superseded()) {
            publish(derived)
            return DetailWriteOutcome.PUBLISHED
        }
        genDir.toFile().deleteRecursively()
        return DetailWriteOutcome.DISCARDED
    }
}
