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
package io.hierograph.graphstore.model

/**
 * A node in the GraphStore hierarchy: module, package, type, member, or external-type placeholder.
 *
 * [key] is the stable, content-derived identity (type FQN, member key, package or artifact key); it
 * replaces Neo4j's internal `id()`. [kind] is an `Any` because it spans [io.hierograph.mcp.javaspec
 * JavaNodeKind] values and the `EXTERNAL_TYPE` string. [parent] is the parent node's key, `null` for
 * top-level modules. A [placeholder] node is a referenced-but-unscanned type; it is upgraded in place
 * when the real class is scanned, so incoming edges stay valid.
 */
data class Node(
    val key: String,
    val kind: Any,
    val name: String,
    val fqn: String,
    val parent: String?,
    val placeholder: Boolean = false,
)
