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
package io.hierograph.graphstore.jvm

import io.hierograph.graphstore.jvm.model.JavaTypeNames

/**
 * Decides which referenced-but-unscanned types the [GraphStore] materializes as in-memory placeholder
 * nodes. This is a projection filter applied **only in memory, at ingest time** — it never affects the
 * snapshot: the originating refs are facts and are always persisted; placeholders are synthesized.
 */
fun interface ExternalTypeFilter {

    /** True if the referenced-but-unscanned type [fqn] becomes an in-memory placeholder node. */
    fun includes(fqn: String): Boolean

    companion object {

        /** Materializes a placeholder for every unresolved reference target. */
        val ALL: ExternalTypeFilter = ExternalTypeFilter { true }

        /**
         * Excludes the types that are referenced almost everywhere and carry no analytical value as graph
         * nodes: primitives, JVM array descriptors, default-package names, `java.lang.*`, `kotlin.*` and
         * the JetBrains nullability annotations. They stay out of the in-memory hierarchy; their refs
         * remain in the snapshot regardless. A genuinely *scanned* `java.lang.String` — one with its own
         * type row — is unaffected: this gates only placeholder synthesis for unscanned targets.
         */
        val EXCLUDE_UBIQUITOUS: ExternalTypeFilter = ExternalTypeFilter { fqn ->
            fqn !in JavaTypeNames.PRIMITIVES &&                 // primitives: int, boolean, void, …
                !fqn.startsWith("[") &&                          // JVM array descriptors: [I, [D, …
                fqn.contains('.') &&                             // default-package names
                !fqn.startsWith("java.lang.") &&
                !fqn.startsWith("kotlin.") &&
                fqn != "org.jetbrains.annotations.NotNull" &&
                fqn != "org.jetbrains.annotations.Nullable"
        }
    }
}
