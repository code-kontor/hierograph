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
package io.hierograph.graphstore.jvm.model

/**
 * One declared member of a type.
 *
 * [key] is the member key `<owner fqn>#<signature>`; [signature] doubles as the display name. [kind] is a member-level [JavaNodeKind] (FIELD, METHOD, CONSTRUCTOR).
 */
data class MemberFacts(
    val key: String,
    val kind: JavaNodeKind,
    val signature: String,
    /** The member's simple name (`place`, `log`, `<init>`), for display — distinct from [signature]. */
    val name: String = "",
    /** Packed access flags; see [MemberModifiers] for the bit layout and decoding. */
    val modifiers: Int = 0,
    val lineFrom: Int? = null,
    val lineTo: Int? = null,
    /** Declared parameters, ordered by [ParameterFacts.index]; empty for fields and no-arg methods. */
    val parameters: List<ParameterFacts> = emptyList(),
    /**
     * For a synthetic bridge method (see [MemberModifiers.isBridge]), the key of the real method it
     * forwards to on the same type — a covariant/generic override's actual implementation. `null` for
     * everything else. Lets load-time resolution point virtual-call/override edges at the real method
     * rather than the bridge trampoline.
     */
    val bridgeTarget: String? = null,
)
