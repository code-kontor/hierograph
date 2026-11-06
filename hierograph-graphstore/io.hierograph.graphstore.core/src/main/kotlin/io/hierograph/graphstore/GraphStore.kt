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

import io.hierograph.graphstore.model.ArtifactInfo
import io.hierograph.graphstore.model.ClassFacts
import io.hierograph.graphstore.model.MemberFacts
import io.hierograph.graphstore.model.Node
import io.hierograph.graphstore.model.Ref
import io.hierograph.graphstore.model.RefKind
import io.hierograph.mcp.javaspec.JavaKinds
import java.util.EnumSet

/**
 * Hierograph's in-memory model, built directly from [ClassFacts] during ingest. It replaces the
 * jQAssistant `GraphDbMapping` projection: every former Cypher rule becomes a few lines of construction
 * logic here, and the hierarchy + type-dependency query surface produces the same FQN-keyed row shapes
 * the downstream layers consume.
 *
 * Identity is by **key string** (type FQN, member key `<owner>#<sig>`, or a synthetic package/artifact
 * key), never by object reference. Updates replace a type's *owned subgraph* (members + outgoing refs)
 * but never delete the type node itself while it still has incoming edges — those are instead downgraded
 * to placeholders so references from unchanged classes stay valid.
 *
 * Not thread-safe; callers coordinate read/write batches (see the spec's concurrency decision).
 */
class GraphStore {

    private val nodesByKey = LinkedHashMap<String, Node>()
    private val childKeys = HashMap<String, MutableSet<String>>()
    private val refsByOwner = HashMap<String, List<Ref>>()
    private val incomingOwners = HashMap<String, MutableSet<String>>()

    init {
        // Synthetic module owning every placeholder; replaces jQAssistant's :Virtual:Artifact.
        putNode(Node(EXTERNAL_ARTIFACT_KEY, JavaKinds.MODULE, EXTERNAL_ARTIFACT_NAME, EXTERNAL_ARTIFACT_NAME, parent = null))
    }

    // ── ingest ─────────────────────────────────────────────────────────

    /** Registers a scanned artifact as a top-level MODULE node (idempotent). */
    fun registerArtifact(artifact: ArtifactInfo) {
        if (artifact.key !in nodesByKey) {
            putNode(Node(artifact.key, JavaKinds.MODULE, artifact.name, artifact.fqn, parent = null))
        }
    }

    /**
     * Replaces the owned subgraph of [facts].fqn under [artifactKey]: removes any previous members and
     * outgoing refs, ensures the package path, upserts the type node (upgrading a placeholder in place),
     * stores members and refs, and creates placeholders for unscanned ref targets.
     */
    fun applyClass(artifactKey: String, facts: ClassFacts) {
        removeOwned(facts.fqn)
        val parentKey = ensurePackagePath(artifactKey, packageOf(facts.fqn))
        upsertType(facts.fqn, facts.kind, parentKey)
        facts.members.forEach { putMember(facts.fqn, it) }
        storeRefs(facts.fqn, facts.refs)
    }

    /**
     * Removes a scanned type's owned subgraph. If the type is still referenced it is downgraded to a
     * placeholder under the external module; otherwise the node is removed outright.
     */
    fun removeClass(fqn: String) {
        removeOwned(fqn)
        val node = nodesByKey[fqn] ?: return
        if (!incomingOwners[fqn].isNullOrEmpty()) {
            val pkgKey = ensurePackagePath(EXTERNAL_ARTIFACT_KEY, packageOf(fqn))
            reparent(fqn, node.parent, pkgKey)
            nodesByKey[fqn] = node.copy(kind = JavaKinds.EXTERNAL_TYPE, parent = pkgKey, placeholder = true)
        } else {
            node.parent?.let { childKeys[it]?.remove(fqn) }
            nodesByKey.remove(fqn)
        }
    }

    /**
     * Reconciles placeholders after a bulk load that added refs before all types were present: any ref
     * target without a node becomes a placeholder. Idempotent; the incremental [applyClass] path already
     * creates placeholders inline, so this is a no-op there.
     */
    fun resolvePlaceholders() {
        val targets = refsByOwner.values.asSequence().flatten().map { typeOf(it.toKey) }.toCollection(HashSet())
        targets.forEach { if (it !in nodesByKey) ensurePlaceholder(it) }
    }

    // ── queries ────────────────────────────────────────────────────────

    /**
     * The hierarchy projection: one [HierarchyRow] per node (including the external module and its
     * placeholders), with each parent resolved to its FQN. The root has no row; top-level modules carry
     * a `null` [HierarchyRow.parentFqn].
     */
    fun hierarchyRows(): Sequence<HierarchyRow> =
        nodesByKey.values.asSequence().map { node ->
            HierarchyRow(node.kind, node.fqn, node.parent?.let { nodesByKey[it]?.fqn })
        }

    /**
     * Aggregated type-to-type dependencies, grouping each owner's refs by target type and summing
     * weights. Self-edges are excluded. See [TypeDependency] for the `other`-flag semantics.
     */
    fun typeDependencies(): Sequence<TypeDependency> =
        refsByOwner.asSequence().flatMap { (owner, refs) ->
            refs.groupBy { typeOf(it.toKey) }
                .asSequence()
                .filter { (target, _) -> target != owner }
                .map { (target, rs) ->
                    val kinds = rs.mapTo(EnumSet.noneOf(RefKind::class.java)) { it.kind }
                    TypeDependency(
                        from = owner,
                        to = target,
                        weight = rs.sumOf { it.count ?: 1 },
                        extends = RefKind.EXTENDS in kinds,
                        implements = RefKind.IMPLEMENTS in kinds,
                        annotatedBy = RefKind.ANNOTATED_BY in kinds,
                        other = kinds.any { it !in RefKind.STRUCTURAL },
                    )
                }
        }

    // ── read access (mainly for tests and diagnostics) ─────────────────

    fun node(key: String): Node? = nodesByKey[key]
    fun contains(key: String): Boolean = key in nodesByKey
    fun childKeysOf(key: String): Set<String> = childKeys[key].orEmpty()
    fun nodeCount(): Int = nodesByKey.size
    fun incomingOwnersOf(fqn: String): Set<String> = incomingOwners[fqn].orEmpty()

    // ── internals ──────────────────────────────────────────────────────

    private fun putNode(node: Node) {
        nodesByKey[node.key] = node
        node.parent?.let { childKeys.getOrPut(it) { linkedSetOf() } += node.key }
    }

    private fun putMember(ownerFqn: String, m: MemberFacts) {
        putNode(Node(m.key, m.kind, m.signature, m.key, parent = ownerFqn))
    }

    /** Upserts a scanned type, upgrading a placeholder in place and re-homing it under [parentKey]. */
    private fun upsertType(fqn: String, kind: Any, parentKey: String) {
        val existing = nodesByKey[fqn]
        if (existing == null) {
            putNode(Node(fqn, kind, simpleName(fqn), fqn, parent = parentKey, placeholder = false))
        } else {
            reparent(fqn, existing.parent, parentKey)
            nodesByKey[fqn] = existing.copy(kind = kind, name = simpleName(fqn), parent = parentKey, placeholder = false)
        }
    }

    private fun ensurePlaceholder(fqn: String) {
        if (fqn in nodesByKey) return
        val pkgKey = ensurePackagePath(EXTERNAL_ARTIFACT_KEY, packageOf(fqn))
        putNode(Node(fqn, JavaKinds.EXTERNAL_TYPE, simpleName(fqn), fqn, parent = pkgKey, placeholder = true))
    }

    private fun storeRefs(ownerFqn: String, refs: List<Ref>) {
        refsByOwner[ownerFqn] = refs
        for (r in refs) {
            val target = typeOf(r.toKey)
            if (target !in nodesByKey) ensurePlaceholder(target)
            incomingOwners.getOrPut(target) { mutableSetOf() } += ownerFqn
        }
    }

    /** Removes [fqn]'s members and outgoing refs (and their incoming bookkeeping); keeps the type node. */
    private fun removeOwned(fqn: String) {
        childKeys.remove(fqn)?.toList()?.forEach { removeNodeCascade(it) }
        refsByOwner.remove(fqn)?.forEach { r ->
            val target = typeOf(r.toKey)
            incomingOwners[target]?.let { owners ->
                owners.remove(fqn)
                if (owners.isEmpty()) incomingOwners.remove(target)
            }
        }
    }

    private fun removeNodeCascade(key: String) {
        childKeys.remove(key)?.forEach { removeNodeCascade(it) }
        nodesByKey.remove(key)
    }

    /** Moves [key] from [oldParent]'s child set to [newParent]'s, if they differ. */
    private fun reparent(key: String, oldParent: String?, newParent: String) {
        if (oldParent == newParent) return
        oldParent?.let { childKeys[it]?.remove(key) }
        childKeys.getOrPut(newParent) { linkedSetOf() } += key
    }

    /** Ensures the package-node chain for [pkgFqn] under [artifactKey]; returns the deepest package key. */
    private fun ensurePackagePath(artifactKey: String, pkgFqn: String): String {
        if (pkgFqn.isEmpty()) return artifactKey
        var parentKey = artifactKey
        val fqn = StringBuilder()
        for (segment in pkgFqn.split('.')) {
            if (fqn.isNotEmpty()) fqn.append('.')
            fqn.append(segment)
            val thisFqn = fqn.toString()
            val key = packageKey(artifactKey, thisFqn)
            if (key !in nodesByKey) {
                putNode(Node(key, JavaKinds.PACKAGE, segment, thisFqn, parent = parentKey))
            }
            parentKey = key
        }
        return parentKey
    }

    private fun packageKey(artifactKey: String, pkgFqn: String) = "pkg:$artifactKey:$pkgFqn"
    private fun packageOf(fqn: String) = fqn.substringBeforeLast('.', "")
    private fun simpleName(fqn: String) = fqn.substringAfterLast('.')

    /** The owning type key of a ref endpoint: the part before '#' for a member key, else the key itself. */
    private fun typeOf(key: String) = key.substringBefore('#')

    companion object {
        const val EXTERNAL_ARTIFACT_KEY = "<external>"
        const val EXTERNAL_ARTIFACT_NAME = "External Types"
    }
}
