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
package io.hierograph.graphservice

import io.hierograph.graphstore.model.DetailRef
import io.hierograph.graphstore.model.MemberModifiers

/**
 * The member-level relationships no single class file can answer — they need the whole type hierarchy —
 * computed **at load, over the assembled graph** (the counterpart of the scanner's scan-time resolver,
 * which can no longer see across independently-scanned `.hierograph` artifacts). Inputs come from the
 * loaded snapshot: each scanned type with its direct supertypes and declared methods, plus the direct
 * `calls` edges. Types outside the scanned set are unknown, so nothing is derived into or through them.
 *
 * - **overrides** — a method overrides, in each transitive supertype, the method with the same signature;
 *   failing that, the methods with the same name and parameter types (a covariant return; the compiler's
 *   bridge method carries the exact parent signature and is matched directly). Constructors, static
 *   initializers, static and private methods take no part.
 * - **virtual calls** — a call to `T#m` may dispatch to any implementation of `m` in a subtype of `T`, so
 *   each such implementation also gets a `calls` edge. Only for a scanned `T` — **external/placeholder
 *   types are not scanned**: we know only the arbitrary handful of their implementations that happen to be
 *   in the set, so fanning a call to e.g. `java.util.Iterator#hasNext()` across them is both unsound and
 *   explosive. No self edges.
 * - **resolved calls** — a call naming a receiver's static type that only *inherits* the method is also
 *   linked to the declaration in the nearest scanned supertype.
 *
 * The algorithm keys off method signatures plus the `static`/`private` flags (from [MemberModifiers]); it
 * does not need the bridge flag, because a covariant override's bridge method is present in its own right
 * with the exact parent signature.
 */
object HierarchyResolution {

    /** A scanned method/constructor: its node key, signature, packed [MemberModifiers], and declaration line. */
    class Method(val key: String, val signature: String, val modifiers: Int, val lineFrom: Int?)

    /**
     * A type in the assembled graph: its FQN, the FQNs of its direct supertypes (super + interfaces), and
     * its methods. [external] marks a placeholder/stub type (referenced but not scanned, so its real
     * subtype set is unknown) — such a type never acts as a virtual-dispatch fan root.
     */
    class Type(val fqn: String, val directSupertypes: List<String>, val methods: List<Method>, val external: Boolean = false)

    fun resolve(types: List<Type>, directCalls: List<DetailRef>): List<DetailRef> {
        val hierarchy = Hierarchy(types)
        return overrides(types, hierarchy) + virtualCalls(directCalls, hierarchy)
    }

    private fun overrides(types: List<Type>, hierarchy: Hierarchy): List<DetailRef> {
        val out = LinkedHashSet<DetailRef>()
        for (c in types) {
            val candidates = c.methods.filter { overridable(it) }
            if (candidates.isEmpty()) continue
            val supertypes = hierarchy.supertypesOf(c.fqn)
            for (m in candidates) for (s in supertypes) {
                val exact = hierarchy.declaredMethod(s, m.signature)?.takeIf { overridable(it) }
                val overridden = if (exact != null) listOf(exact) else hierarchy.overridableMethods(s)[nameAndParameters(m.signature)].orEmpty()
                overridden.forEach { out += DetailRef(m.key, c.fqn, it.key, s, "overrides", m.lineFrom) }
            }
        }
        return out.toList()
    }

    private fun virtualCalls(directCalls: List<DetailRef>, hierarchy: Hierarchy): List<DetailRef> {
        val out = LinkedHashSet<DetailRef>()
        for (call in directCalls) {
            if (!hierarchy.isScanned(call.toType)) continue
            val signature = call.toKey.substringAfter('#')
            if (hierarchy.declaredMethod(call.toType, signature) == null) {
                hierarchy.inheritedDeclaration(call.toType, signature)?.let { (declarer, m) ->
                    if (m.key != call.fromKey) out += DetailRef(call.fromKey, call.fromType, m.key, declarer, "calls", call.line)
                }
            }
            if (signature.contains(" <init>(")) continue
            // A static method (declared on the call's owner) does not dispatch virtually.
            if (hierarchy.declaredMethod(call.toType, signature)?.let { it.modifiers and MemberModifiers.STATIC != 0 } == true) continue
            for (sub in hierarchy.subtypesOf(call.toType)) {
                val impl = hierarchy.declaredMethod(sub, signature)?.takeIf { overridable(it) } ?: continue
                if (impl.key != call.fromKey) out += DetailRef(call.fromKey, call.fromType, impl.key, sub, "calls", call.line)
            }
        }
        return out.toList()
    }

    private fun overridable(m: Method): Boolean =
        m.modifiers and (MemberModifiers.STATIC or MemberModifiers.PRIVATE) == 0 &&
            !m.signature.contains(" <init>(") && !m.signature.contains(" <clinit>(")

    /** `java.lang.Object getObject()` → `getObject()`: what an override has to match (returns may be covariant). */
    private fun nameAndParameters(signature: String): String = signature.substringAfter(' ')

    /** Supertype/subtype navigation and per-type method tables over the scanned set (first definer per FQN). */
    private class Hierarchy(types: List<Type>) {
        private val byFqn = HashMap<String, Type>().apply { types.forEach { putIfAbsent(it.fqn, it) } }
        private val externalFqns = types.asSequence().filter { it.external }.mapTo(HashSet()) { it.fqn }
        private val directSubtypes = HashMap<String, MutableList<String>>().apply {
            byFqn.values.forEach { c -> c.directSupertypes.forEach { getOrPut(it) { ArrayList() } += c.fqn } }
        }
        private val supertypes = HashMap<String, List<String>>()
        private val subtypes = HashMap<String, List<String>>()
        private val overridable = HashMap<String, Map<String, List<Method>>>()
        private val declared = HashMap<String, Map<String, Method>>()

        fun supertypesOf(fqn: String): List<String> = supertypes.getOrPut(fqn) {
            walk(fqn) { byFqn[it]?.directSupertypes.orEmpty() }
        }

        fun subtypesOf(fqn: String): List<String> = subtypes.getOrPut(fqn) { walk(fqn) { directSubtypes[it].orEmpty() } }

        fun overridableMethods(fqn: String): Map<String, List<Method>> = overridable.getOrPut(fqn) {
            byFqn[fqn]?.methods.orEmpty().filter { overridable(it) }.groupBy { nameAndParameters(it.signature) }
        }

        fun isScanned(fqn: String): Boolean = fqn in byFqn && fqn !in externalFqns

        fun inheritedDeclaration(fqn: String, signature: String): Pair<String, Method>? =
            supertypesOf(fqn).firstNotNullOfOrNull { s ->
                declaredMethod(s, signature)?.takeIf { it.modifiers and MemberModifiers.PRIVATE == 0 }?.let { s to it }
            }

        fun declaredMethod(fqn: String, signature: String): Method? =
            declared.getOrPut(fqn) { byFqn[fqn]?.methods.orEmpty().associateBy { it.signature } }[signature]

        private fun walk(start: String, next: (String) -> List<String>): List<String> {
            val seen = LinkedHashSet<String>()
            val queue = ArrayDeque(next(start))
            while (queue.isNotEmpty()) {
                val t = queue.removeFirst()
                if (t == start || !seen.add(t)) continue
                if (t in byFqn) queue.addAll(next(t))
            }
            return seen.filter { it in byFqn }
        }
    }
}
