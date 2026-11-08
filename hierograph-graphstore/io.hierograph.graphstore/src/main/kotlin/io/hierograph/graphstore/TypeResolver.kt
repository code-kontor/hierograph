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
package io.hierograph.graphstore

/**
 * Decides which artifact provides a referenced type FQN, when more than one artifact defines it. This is
 * the resolution seam for **classpath shadowing**: a reference carries only a bare FQN, and the same FQN
 * can be defined by several artifacts (shaded jars, duplicate classes, multi-release), so something must
 * choose the binding. In the JVM that "something" is classpath order — the first artifact on the
 * consumer's classpath that defines the FQN wins.
 *
 * [classpathOrder] implements exactly that rule from each owner's recorded classpath; [OWNER_FIRST] is
 * the deterministic default when no classpath is known. With no duplicated FQN (the common case) every
 * resolver agrees.
 */
fun interface TypeResolver {

    /**
     * The artifact that provides [fqn] for a reference owned by [ownerArtifactKey], chosen from the
     * artifacts that actually define it ([definers], always non-empty).
     */
    fun resolve(ownerArtifactKey: String, fqn: String, definers: Set<String>): String

    companion object {
        /**
         * Owner-first approximation of classpath order, for when no classpath is known: a reference binds
         * to the owner's own artifact when it defines the FQN (its own output is first on its classpath),
         * otherwise to the lexicographically smallest definer so the choice is deterministic across runs.
         */
        val OWNER_FIRST = TypeResolver { ownerArtifactKey, _, definers ->
            if (ownerArtifactKey in definers) ownerArtifactKey else definers.minOrNull()!!
        }

        /**
         * Real classpath-order resolution from the Maven plugin's manifest: [classpathByOwner] maps each
         * owner artifact to its classpath in order (index 0 = the owner itself, then its dependencies). A
         * reference binds to the first artifact on the owner's classpath that defines the FQN — exactly how
         * the JVM picks a class. Falls back to [OWNER_FIRST] for an owner with no recorded classpath (e.g.
         * a CLI snapshot, or an artifact the manifest did not cover).
         */
        fun classpathOrder(classpathByOwner: Map<String, List<String>>): TypeResolver =
            TypeResolver { ownerArtifactKey, fqn, definers ->
                classpathByOwner[ownerArtifactKey]?.firstOrNull { it in definers }
                    ?: OWNER_FIRST.resolve(ownerArtifactKey, fqn, definers)
            }
    }
}
