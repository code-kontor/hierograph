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
package io.hierograph.graphstore.jvm.archive

/**
 * A snapshot artifact that this code cannot load because it was written in another format version (see
 * [ParquetSchema.FORMAT_VERSION]). A stale artifact — format 1, or no version at all — has to be rescanned
 * from its classes; one in a newer format needs a newer Hierograph.
 */
class SnapshotFormatException(message: String) : IllegalStateException(message) {
    companion object {
        /** An artifact written by an older Hierograph; [reason] says how that was recognised. */
        fun stale(artifact: String, reason: String) = SnapshotFormatException(
            "Snapshot artifact $artifact was written by an older Hierograph ($reason); this Hierograph reads " +
                "format ${ParquetSchema.FORMAT_VERSION}. Rescan it to load it.",
        )
    }
}
