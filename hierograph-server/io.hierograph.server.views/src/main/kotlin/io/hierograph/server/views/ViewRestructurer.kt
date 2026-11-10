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
package io.hierograph.server.views

import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.INodeSource
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.server.views.ViewFinding.Code

/** The source of a virtual module or package: a node that exists only in a restructuring view. */
class VirtualNodeSource(
    override val identifier: Any,
    override val name: String,
    override val qualifiedName: String,
) : INodeSource {
    override var node: HGNode? = null
}

/** The identities of virtual modules and packages (views specification, §6.4.3). */
object ViewNodes {

    /** A virtual module's handle: `view:<view>:module:<name>`. */
    fun moduleHandle(view: String, name: String): Long = NodeHandle.of("view:$view:module:$name")

    /** A virtual package's handle: `view:<view>:package:<module>/<fqn>`, the module by its qualified name. */
    fun packageHandle(view: String, module: String, fqn: String): Long = NodeHandle.of("view:$view:package:$module/$fqn")

    /** Whether [node] is a virtual module or package of a view. */
    fun isVirtual(node: HGNode): Boolean = node.nodeSource is VirtualNodeSource
}

/** A dependency between two modules, by qualified name, with its type-level weight. */
data class ModuleDependency(val from: String, val to: String, val weight: Int)

/** A dependency between two modules whose weight a restructuring changed. */
data class ChangedModuleDependency(val from: String, val to: String, val before: Int, val after: Int)

/**
 * What a restructuring does to the modules (views specification, §6.4.4): the module dependencies it adds,
 * removes and changes compared with the base hierarchy, and the module cycles before and after. A cycle is
 * the sorted qualified names of a strongly connected component of more than one module.
 */
data class RestructuringEffect(
    val added: List<ModuleDependency>,
    val removed: List<ModuleDependency>,
    val changed: List<ChangedModuleDependency>,
    val cycles: List<List<String>>,
    val baseCycles: List<List<String>>,
) {
    /**
     * The cycles the restructuring creates: those whose modules were not all in one cycle of the base hierarchy.
     * A base cycle that a move shrinks is not new; one that gains a module, virtual or real, or that merges with
     * another, is: modules that could be built apart before now cannot.
     */
    val newCycles: List<List<String>> get() = cycles.filter { cycle -> baseCycles.none { it.containsAll(cycle) } }
}

/**
 * The moves of a restructuring view applied to a fork of a model: [model] is the fork, [virtualModules]
 * maps each virtual module's name to its node. When [applied] is `false` (a handle collision), [model] is
 * the base model and nothing was changed.
 */
class Restructured(
    val model: HGModel,
    val virtualModules: Map<String, HGNode>,
    val findings: List<ViewFinding>,
    val effect: RestructuringEffect?,
    val applied: Boolean,
)

/**
 * Applies the modules and moves of a restructuring view (views specification, §6.4) to a fork of a model.
 *
 * Selectors are resolved against the base hierarchy; targets against the restructured one, when the move
 * is applied, so a move may target a package an earlier move created. Moves run in document order. A move
 * changes where a node sits, never what it depends on; real nodes keep their handles.
 */
object ViewRestructurer {

    fun restructure(base: HGModel, document: ViewDocument): Restructured = Run(base, document).run()

    private class Collision(val handle: Long, val taken: HGNode, val what: String) : RuntimeException()

    private class Run(private val base: HGModel, private val document: ViewDocument) {

        private val view = document.name
        private val fork = base.fork()
        private val h: Hierarchy = fork.hierarchy
        private val findings = mutableListOf<ViewFinding>()
        private val virtualModules = LinkedHashMap<String, HGNode>()

        /** Every node some move selects; a nested type that is selected itself does not follow its enclosing type. */
        private val selected = HashSet<HGNode>()

        /** Where nodes were moved from, and the virtual packages created: candidates for removal when empty. */
        private val vacated = LinkedHashSet<HGNode>()

        /** Real modules something was moved out of. */
        private val sourceModules = LinkedHashSet<HGNode>()

        fun run(): Restructured {
            try {
                createModules()
                val selections = select()
                for ((i, move) in document.moves.withIndex()) apply("moves[$i]", move, selections[i])
                removeEmptyPackages()
            } catch (c: Collision) {
                val finding = finding(
                    Code.VIRTUAL_HANDLE_COLLISION, c.what,
                    "the handle of the virtual ${c.what} is already taken by ${NodeMetadata.getQualifiedName(c.taken)}; the view is not applied",
                    nodes = listOf(c.taken),
                )
                return Restructured(base, emptyMap(), findings + finding, null, applied = false)
            }
            reportModules()
            reportSplitPackages()
            return Restructured(fork, virtualModules, findings.toList(), effect(), applied = true)
        }

        // ── modules ──────────────────────────────────────────────────

        private fun createModules() {
            for ((i, module) in document.modules.withIndex()) {
                val handle = ViewNodes.moduleHandle(view, module.name)
                h.lookupNode(handle)?.let { throw Collision(handle, it, "module '${module.name}' (modules[$i])") }
                val node = h.createLocalNode(JavaKinds.MODULE) { VirtualNodeSource(handle, module.name, module.name) }
                h.addChild(h.rootNode, node)
                virtualModules[module.name] = node
            }
        }

        private fun reportModules() {
            for ((i, module) in document.modules.withIndex()) {
                val node = virtualModules.getValue(module.name)
                if (h.childrenOf(node).isEmpty()) {
                    findings += finding(Code.VIRTUAL_MODULE_EMPTY, "modules[$i]", "the virtual module '${module.name}' receives nothing; it stays empty", nodes = listOf(node))
                }
            }
            for (module in sourceModules) {
                if (h.lookupNode(module.identifier) != null && h.childrenOf(module).isEmpty()) {
                    findings += finding(
                        Code.REAL_MODULE_EMPTY, "moves",
                        "the module ${qn(module)} is left empty; it stays in the view",
                        nodes = listOf(module),
                    )
                }
            }
        }

        // ── selection ────────────────────────────────────────────────

        /** The nodes each move moves: its selection without nested selections and without nodes an earlier move takes. */
        private fun select(): List<List<HGNode>> {
            val resolver = ViewResolver(base.hierarchy)
            val firstMove = HashMap<HGNode, Int>()
            return document.moves.mapIndexed { i, move ->
                val label = "moves[$i]"
                val matching = resolver.matching(move.select)
                if (matching.nodes.isEmpty()) {
                    val why = if (matching.withinMatchedNothing) "its 'within' matches no node" else "it selects nothing"
                    findings += finding(Code.MOVE_SELECTS_NOTHING, label, "the move of ${move.select} is ignored: $why", move.select)
                }
                val set = matching.nodes.toSet()
                // A node inside another node of the same selection moves with it.
                val outermost = matching.nodes.filter { node -> base.hierarchy.predecessorsOf(node).none { it in set } }
                outermost.filter { node ->
                    val earlier = firstMove[node]
                    if (earlier != null) {
                        findings += finding(
                            Code.NODE_MOVED_TWICE, label,
                            "${qn(node)} is already moved by moves[$earlier]; this move leaves it there", move.select, listOf(node),
                        )
                        false
                    } else {
                        firstMove[node] = i
                        true
                    }
                }.also { selected += it }
            }
        }

        // ── targets ──────────────────────────────────────────────────

        private sealed interface Target {
            val module: HGNode
        }

        private class ModuleTarget(override val module: HGNode) : Target

        private class PackageTarget(override val module: HGNode, val pkg: HGNode) : Target

        private fun target(label: String, move: MoveDefinition): Target? {
            val moduleSpec = move.to.substringBefore(MoveDefinition.PACKAGE_SEPARATOR)
            val packageFqn = move.to.substringAfter(MoveDefinition.PACKAGE_SEPARATOR, "").takeIf { it.isNotEmpty() }
            val module = virtualModules[moduleSpec] ?: run {
                val pattern = NamePattern.compile(moduleSpec)
                val modules = h.childrenOf(h.rootNode).filter { it.kind == JavaKinds.MODULE && !ViewNodes.isVirtual(it) && pattern.matches(qn(it)) }
                when (modules.size) {
                    1 -> modules.single()
                    0 -> {
                        findings += finding(Code.UNKNOWN_MOVE_TARGET, label, "the target '${move.to}' names no module or virtual module; the move is skipped", move.select)
                        return null
                    }
                    else -> {
                        findings += finding(
                            Code.AMBIGUOUS_MOVE_TARGET, label,
                            "the target '${move.to}' matches ${modules.size} modules; the move is skipped", move.select, modules,
                        )
                        return null
                    }
                }
            }
            return if (packageFqn == null) ModuleTarget(module) else PackageTarget(module, ensurePackage(module, packageFqn))
        }

        // ── moves ────────────────────────────────────────────────────

        private fun apply(label: String, move: MoveDefinition, nodes: List<HGNode>) {
            if (nodes.isEmpty()) return
            val target = target(label, move) ?: return
            for (node in nodes) {
                when {
                    node.kind == JavaKinds.MODULE -> when (target) {
                        is ModuleTarget -> if (node !== target.module) mergeModule(node, target.module)
                        is PackageTarget -> cannotMove(label, move, node, "a module can only be merged into a module")
                    }
                    node.kind == JavaKinds.PACKAGE -> when (target) {
                        is ModuleTarget -> packageToModule(node, target.module)
                        is PackageTarget ->
                            if (target.pkg === node || h.isPredecessorOf(node, target.pkg)) {
                                findings += finding(
                                    Code.MOVE_INTO_OWN_SUBTREE, label,
                                    "${qn(node)} cannot move into ${qn(target.pkg)}, which lies inside it; skipped", move.select, listOf(node),
                                )
                            } else {
                                packageToPackage(node, target.pkg, target.module)
                            }
                    }
                    node.kind in JavaKinds.TYPE_KINDS -> when (target) {
                        is ModuleTarget -> moveType(node, packageIn(target.module, packageOf(qn(node))))
                        is PackageTarget -> moveType(node, target.pkg)
                    }
                    else -> cannotMove(label, move, node, "only modules, packages and scanned types can be moved")
                }
            }
        }

        private fun cannotMove(label: String, move: MoveDefinition, node: HGNode, why: String) {
            findings += finding(Code.CANNOT_MOVE, label, "${qn(node)} (${node.kind}) is not moved: $why", move.select, listOf(node))
        }

        /** Moves [node] below [parent], remembering where it came from. */
        private fun relocate(node: HGNode, parent: HGNode) {
            val from = h.parentOf(node)
            if (from === parent) return
            if (from != null) {
                vacated += from
                moduleOf(from)?.takeIf { !ViewNodes.isVirtual(it) }?.let { sourceModules += it }
            }
            h.move(node, parent)
        }

        /** Merges module [source] into [target]: its packages and types move in, and [source] leaves the view. */
        private fun mergeModule(source: HGNode, target: HGNode) {
            for (child in h.childrenOf(source).toList()) {
                if (h.parentOf(child) !== source) continue // moved along with an enclosing type
                when {
                    child.kind == JavaKinds.PACKAGE -> packageToModule(child, target)
                    child.kind in JavaKinds.TYPE_KINDS -> moveType(child, target)
                    else -> relocate(child, target)
                }
            }
            pruneEmptyPackages(source)
            h.remove(source)
            sourceModules -= source
        }

        /** Removes the packages below [node] that are empty, bottom-up; merging leaves such shells behind. */
        private fun pruneEmptyPackages(node: HGNode) {
            for (child in h.childrenOf(node).toList()) {
                if (child.kind != JavaKinds.PACKAGE) continue
                pruneEmptyPackages(child)
                if (h.childrenOf(child).isEmpty()) h.remove(child)
            }
        }

        /**
         * Moves package [pkg] into [module] under its own qualified name: the package path above it is reused
         * where the module has it and created as virtual packages where it does not, and a package of the same
         * name already there absorbs [pkg]'s children.
         */
        private fun packageToModule(pkg: HGNode, module: HGNode) {
            val parent = packageIn(module, packageOf(qn(pkg)))
            val existing = h.childrenOf(parent).firstOrNull { it !== pkg && it.kind == JavaKinds.PACKAGE && qn(it) == qn(pkg) }
            if (existing == null) relocate(pkg, parent) else merge(pkg, existing)
        }

        /** Moves the children of package [source] into the package [target] of the same name, recursively. */
        private fun merge(source: HGNode, target: HGNode) {
            for (child in h.childrenOf(source).toList()) {
                val same = if (child.kind == JavaKinds.PACKAGE) {
                    h.childrenOf(target).firstOrNull { it.kind == JavaKinds.PACKAGE && qn(it) == qn(child) }
                } else {
                    null
                }
                if (same != null) merge(child, same) else relocate(child, target)
            }
            vacated += source
        }

        /**
         * Moves package [pkg] into package [target] as a sub-package: its qualified name in the view becomes
         * the target's name plus its simple name. Renaming a package means a virtual package of the new name
         * takes its children (sub-packages renamed the same way); the emptied package leaves the view.
         */
        private fun packageToPackage(pkg: HGNode, target: HGNode, module: HGNode) =
            rename(pkg, qn(target) + "." + NodeMetadata.getName(pkg), target, module)

        private fun rename(source: HGNode, fqn: String, under: HGNode, module: HGNode) {
            val destination = h.childrenOf(under).firstOrNull { it.kind == JavaKinds.PACKAGE && qn(it) == fqn }
                ?: createPackage(module, fqn, under)
            if (destination === source) return
            for (child in h.childrenOf(source).toList()) {
                if (child.kind == JavaKinds.PACKAGE) rename(child, fqn + "." + NodeMetadata.getName(child), destination, module)
                else relocate(child, destination)
            }
            vacated += source
        }

        /** Moves type [type] below [parent], with the types nested in it that no move selects on their own. */
        private fun moveType(type: HGNode, parent: HGNode) {
            val prefix = NodeMetadata.getName(type) + '$'
            val nested = h.parentOf(type)?.let { from ->
                h.childrenOf(from).filter {
                    it !== type && it.kind in JavaKinds.TYPE_KINDS && NodeMetadata.getName(it).startsWith(prefix) && it !in selected
                }
            }.orEmpty()
            relocate(type, parent)
            for (n in nested) relocate(n, parent)
        }

        // ── packages ─────────────────────────────────────────────────

        /** The package [fqn] in [module], with every package above it, created as virtual packages where missing; [module] for the default package. */
        private fun packageIn(module: HGNode, fqn: String): HGNode = if (fqn.isEmpty()) module else ensurePackage(module, fqn)

        private fun ensurePackage(module: HGNode, fqn: String): HGNode {
            var current = module
            var prefix = ""
            for (segment in fqn.split('.')) {
                prefix = if (prefix.isEmpty()) segment else "$prefix.$segment"
                val name = prefix
                current = h.childrenOf(current).firstOrNull { it.kind == JavaKinds.PACKAGE && qn(it) == name }
                    ?: createPackage(module, name, current)
            }
            return current
        }

        private fun createPackage(module: HGNode, fqn: String, under: HGNode): HGNode {
            val handle = ViewNodes.packageHandle(view, qn(module), fqn)
            h.lookupNode(handle)?.let { throw Collision(handle, it, "package $fqn in ${qn(module)}") }
            val node = h.createLocalNode(JavaKinds.PACKAGE) { VirtualNodeSource(handle, fqn.substringAfterLast('.'), fqn) }
            h.addChild(under, node)
            vacated += node
            return node
        }

        /** Removes packages the moves left empty, and the packages above them that become empty in turn. */
        private fun removeEmptyPackages() {
            for (start in vacated) {
                var node: HGNode? = start
                while (node != null && node.kind == JavaKinds.PACKAGE && h.lookupNode(node.identifier) === node && h.childrenOf(node).isEmpty()) {
                    val parent = h.parentOf(node)
                    h.remove(node)
                    node = parent
                }
            }
        }

        // ── report ───────────────────────────────────────────────────

        /** Packages with types that the view has in more modules than the base hierarchy. */
        private fun reportSplitPackages() {
            val before = packageModules(base.hierarchy)
            for ((fqn, modules) in packageModules(h)) {
                if (modules.size < 2) continue
                val was = before[fqn].orEmpty()
                if (modules.keys.all { it in was.keys }) continue
                findings += finding(
                    Code.SPLIT_PACKAGE, "moves",
                    "the package $fqn now has types in ${modules.size} modules: ${modules.keys.joinToString()}",
                    nodes = modules.values.toList(),
                )
            }
        }

        /** For each package with types: the modules (by qualified name) holding such a package, with the package node. */
        private fun packageModules(hierarchy: Hierarchy): Map<String, Map<String, HGNode>> {
            val result = LinkedHashMap<String, LinkedHashMap<String, HGNode>>()
            for (module in hierarchy.childrenOf(hierarchy.rootNode)) {
                if (module.kind != JavaKinds.MODULE) continue
                hierarchy.traverse(module) { node ->
                    if (node.kind == JavaKinds.PACKAGE && hierarchy.childrenOf(node).any { it.kind in JavaKinds.TYPE_KINDS }) {
                        result.getOrPut(qn(node)) { LinkedHashMap() }.putIfAbsent(qn(module), node)
                    }
                }
            }
            return result
        }

        private fun effect(): RestructuringEffect {
            val before = moduleDependencies(base.hierarchy)
            val after = moduleDependencies(h)
            val added = after.filterKeys { it !in before }.map { (k, w) -> ModuleDependency(k.first, k.second, w) }
            val removed = before.filterKeys { it !in after }.map { (k, w) -> ModuleDependency(k.first, k.second, w) }
            val changed = after.filterKeys { it in before && before.getValue(it) != after.getValue(it) }
                .map { (k, w) -> ChangedModuleDependency(k.first, k.second, before.getValue(k), w) }
            return RestructuringEffect(
                added.sortedWith(compareBy({ it.from }, { it.to })),
                removed.sortedWith(compareBy({ it.from }, { it.to })),
                changed.sortedWith(compareBy({ it.from }, { it.to })),
                cycles(after.keys),
                cycles(before.keys),
            )
        }

        /** The type-level weight between every two modules of [hierarchy], by qualified name. */
        private fun moduleDependencies(hierarchy: Hierarchy): Map<Pair<String, String>, Int> {
            val moduleOf = HashMap<HGNode, HGNode>()
            val modules = hierarchy.childrenOf(hierarchy.rootNode).filter { it.kind == JavaKinds.MODULE }
            for (module in modules) hierarchy.traverse(module) { moduleOf[it] = module }
            val weights = LinkedHashMap<Pair<String, String>, Int>()
            for (module in modules) {
                for (dependency in hierarchy.accumulatedOutgoing(module)) {
                    val target = moduleOf[dependency.to] ?: continue
                    if (target === module) continue
                    weights.merge(qn(module) to qn(target), dependency.weight, Int::plus)
                }
            }
            return weights
        }

        /** The strongly connected components of more than one module, each as sorted names, in sorted order. */
        private fun cycles(edges: Collection<Pair<String, String>>): List<List<String>> {
            val successors = edges.groupBy({ it.first }, { it.second })
            val vertices = (edges.map { it.first } + edges.map { it.second }).distinct().sorted()
            val index = HashMap<String, Int>()
            val low = HashMap<String, Int>()
            val onStack = HashSet<String>()
            val stack = ArrayDeque<String>()
            val components = mutableListOf<List<String>>()
            var next = 0
            fun connect(v: String) {
                index[v] = next; low[v] = next; next++
                stack.addLast(v); onStack += v
                for (w in successors[v].orEmpty()) {
                    if (w !in index) {
                        connect(w)
                        low[v] = minOf(low.getValue(v), low.getValue(w))
                    } else if (w in onStack) {
                        low[v] = minOf(low.getValue(v), index.getValue(w))
                    }
                }
                if (low[v] == index[v]) {
                    val component = mutableListOf<String>()
                    do {
                        val w = stack.removeLast()
                        onStack -= w
                        component += w
                    } while (w != v)
                    if (component.size > 1) components += component.sorted()
                }
            }
            for (v in vertices) if (v !in index) connect(v)
            return components.sortedBy { it.first() }
        }

        // ── helpers ──────────────────────────────────────────────────

        private fun moduleOf(node: HGNode): HGNode? {
            var n: HGNode? = node
            while (n != null && n.kind != JavaKinds.MODULE) n = h.parentOf(n)
            return n
        }

        private fun qn(node: HGNode) = NodeMetadata.getQualifiedName(node)

        private fun packageOf(fqn: String) = fqn.substringBeforeLast('.', "")

        private fun finding(code: Code, where: String, message: String, selector: MemberSelector? = null, nodes: List<HGNode> = emptyList()) =
            ViewFinding(code.severity, code, where, message, selector, nodes)
    }
}
