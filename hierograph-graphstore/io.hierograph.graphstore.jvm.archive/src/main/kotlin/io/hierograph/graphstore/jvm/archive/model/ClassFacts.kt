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
package io.hierograph.graphstore.jvm.archive.model

/**
 * The central contract: one immutable record per class file.
 *
 * The scanner produces it, `SnapshotLoader` rebuilds it from the snapshot tables, and the GraphStore
 * consumes it via `GraphStore.applyClass` (both in the store module). It holds facts only: the class's
 * dependencies are its [Dependency] facts, which travel beside it, and their type-level aggregate is
 * rolled up by whoever loads them into a GraphStore.
 * [kind] is a type-level [JavaNodeKind]
 * (CLASS, INTERFACE, ENUM, ANNOTATION, RECORD). [classHash] is the sha256 of the bytecode and backs
 * the change detector.
 */
data class ClassFacts(
    val fqn: String,
    val kind: JavaNodeKind,
    val modifiers: Int,
    val members: List<MemberFacts>,
    val classHash: String,
    val sourceFile: String? = null,
)
