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
package io.hierograph.graphstore.scanner

import io.hierograph.graphstore.model.DetailRef
import org.objectweb.asm.Opcodes

/**
 * The member-level relationships a single class file cannot answer, computed over the whole scanned set
 * because they need the type hierarchy. Types outside the scanned set are unknown, so nothing is derived
 * into or through them (as with jQAssistant).
 *
 * - **overrides** — a method overrides, in each transitive supertype, the method with the same signature;
 *   failing that, the methods with the same name and parameter types (a covariant return). Constructors,
 *   static initializers, static and private methods take no part. A compiler-generated bridge method and
 *   the real method it forwards to both appear, each with its own edges, as in jQAssistant.
 * - **virtual calls** — a call to `T#m` may dispatch to any implementation of `m` in a subtype of `T`, so
 *   each such implementation gets a `calls` edge from the calling method as well (jQAssistant's
 *   `VIRTUAL_INVOKES`, which the detail layer has always treated as `calls`). Only for a scanned `T`: a
 *   call on `java.lang.Object` or `java.util.List` would otherwise fan out to every scanned implementation.
 *   The implementation is the subtype's method with exactly the called signature (for a covariant
 *   override that is the bridge, which forwards to the real method). No self edges: a method implementing
 *   the very interface it calls through does not call itself.
 * - **resolved calls** — a call names the receiver's static type, which may only *inherit* the method
 *   (`resolver.setSuffix(…)` on an `InternalResourceViewResolver`). As the JVM does, the call is also
 *   linked to the declaration in the nearest scanned supertype (`UrlBasedViewResolver#setSuffix`).
 */
internal object HierarchyResolver {

    fun resolve(classes: List<ScannedClass>, directCalls: List<DetailRef>): List<DetailRef> {
        val hierarchy = Hierarchy(classes)
        return overrides(classes, hierarchy) + virtualCalls(directCalls, hierarchy)
    }

    private fun overrides(classes: List<ScannedClass>, hierarchy: Hierarchy): List<DetailRef> {
        val out = LinkedHashSet<DetailRef>()
        for (c in classes) {
            val candidates = c.methods.filter { overridable(it) }
            if (candidates.isEmpty()) continue
            val supertypes = hierarchy.supertypesOf(c.facts.fqn)
            for (m in candidates) for (s in supertypes) {
                val exact = hierarchy.declaredMethod(s, m.signature)?.takeIf { overridable(it) }
                val overridden = if (exact != null) listOf(exact) else hierarchy.overridableMethods(s)[nameAndParameters(m.signature)].orEmpty()
                overridden.forEach { out += DetailRef(m.key, c.facts.fqn, it.key, s, "overrides", m.lineFrom) }
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
            if (hierarchy.declaredMethod(call.toType, signature)?.let { it.access and Opcodes.ACC_STATIC != 0 } == true) continue
            for (sub in hierarchy.subtypesOf(call.toType)) {
                val impl = hierarchy.declaredMethod(sub, signature)?.takeIf { overridable(it) } ?: continue
                if (impl.key != call.fromKey) out += DetailRef(call.fromKey, call.fromType, impl.key, sub, "calls", call.line)
            }
        }
        return out.toList()
    }

    private fun overridable(m: ScannedClass.MethodInfo): Boolean =
        m.access and (Opcodes.ACC_STATIC or Opcodes.ACC_PRIVATE) == 0 &&
            !m.signature.contains(" <init>(") && !m.signature.contains(" <clinit>(")

    /** `java.lang.Object getObject()` → `getObject()`: what an override has to match (returns may be covariant). */
    private fun nameAndParameters(signature: String): String = signature.substringAfter(' ')

    /** Supertype/subtype navigation and per-type method tables over the scanned set (first definer per FQN). */
    private class Hierarchy(classes: List<ScannedClass>) {
        private val byFqn = HashMap<String, ScannedClass>().apply { classes.forEach { putIfAbsent(it.facts.fqn, it) } }
        private val directSubtypes = HashMap<String, MutableList<String>>().apply {
            byFqn.values.forEach { c -> (listOfNotNull(c.superFqn) + c.interfaceFqns).forEach { getOrPut(it) { ArrayList() } += c.facts.fqn } }
        }
        private val supertypes = HashMap<String, List<String>>()
        private val subtypes = HashMap<String, List<String>>()
        private val overridable = HashMap<String, Map<String, List<ScannedClass.MethodInfo>>>()
        private val declared = HashMap<String, Map<String, ScannedClass.MethodInfo>>()

        /** Scanned transitive supertypes, nearest first, each once. */
        fun supertypesOf(fqn: String): List<String> = supertypes.getOrPut(fqn) {
            walk(fqn) { t -> byFqn[t]?.let { listOfNotNull(it.superFqn) + it.interfaceFqns }.orEmpty() }
        }

        /** Scanned transitive subtypes, nearest first, each once. */
        fun subtypesOf(fqn: String): List<String> = subtypes.getOrPut(fqn) { walk(fqn) { directSubtypes[it].orEmpty() } }

        /** Overridable methods by name and parameter types — more than one where a bridge sits beside its real method. */
        fun overridableMethods(fqn: String): Map<String, List<ScannedClass.MethodInfo>> = overridable.getOrPut(fqn) {
            byFqn[fqn]?.methods.orEmpty().filter { overridable(it) }.groupBy { nameAndParameters(it.signature) }
        }

        fun isScanned(fqn: String): Boolean = fqn in byFqn

        /** The nearest scanned supertype declaring [signature] (inheritable: not private), with that declaration. */
        fun inheritedDeclaration(fqn: String, signature: String): Pair<String, ScannedClass.MethodInfo>? =
            supertypesOf(fqn).firstNotNullOfOrNull { s ->
                declaredMethod(s, signature)?.takeIf { it.access and Opcodes.ACC_PRIVATE == 0 }?.let { s to it }
            }

        fun declaredMethod(fqn: String, signature: String): ScannedClass.MethodInfo? =
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
