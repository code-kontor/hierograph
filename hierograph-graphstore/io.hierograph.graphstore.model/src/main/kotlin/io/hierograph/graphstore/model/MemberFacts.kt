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

import io.hierograph.mcp.javaspec.JavaNodeKind

/**
 * One declared member of a type.
 *
 * [key] is the member key `<owner fqn>#<signature>`; [signature] doubles as the display name, matching
 * the current jQAssistant mapping. [kind] is a member-level [JavaNodeKind] (FIELD, METHOD, CONSTRUCTOR).
 */
data class MemberFacts(
    val key: String,
    val kind: JavaNodeKind,
    val signature: String,
    val modifiers: Int = 0,
    val lineFrom: Int? = null,
    val lineTo: Int? = null,
)
