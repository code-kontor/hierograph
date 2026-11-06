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
 * **Identity is artifact-scoped.** A type node's [Node.key] is `type:<artifact>:<fqn>`, not the bare FQN,
 * because the same fully-qualified name can be defined in more than one artifact (shaded jars, duplicate
 * classes); each becomes a distinct node under its own artifact. Packages (`pkg:<artifact>:<fqn>`) and
 * modules (the artifact key) were already artifact-scoped. A reference, however, carries only a bare FQN,
 * so it is **resolved** to a concrete type node via [resolver] (classpath shadowing; see [TypeResolver]) —
 * the two query shapes expose this both ways: [typeDependencies] projects FQN endpoints (for diffing and
 * display), [typeDependencyEdges] the resolved artifact-scoped node keys (for the hierarchical graph).
 *
 * Updates replace a type's *owned subgraph* (members + outgoing refs) but never delete the type node
 * itself while it still has incoming edges — those are instead downgraded to placeholders so references
 * from unchanged classes stay valid.
 *
 * The in-memory graph is **type-level**: modules, packages, types and type-to-type refs. Members are
 * *not* materialized here — they live in the snapshot and are a strictly on-demand (detail) concern, so
 * the live graph stays small. [placeholderPolicy] decides which unscanned ref targets become in-memory
 * placeholder nodes; it is a projection policy only and never affects what the snapshot persists.
 *
 * Not thread-safe; callers coordinate read/write batches (see the spec's concurrency decision).
 */
class GraphStore(
    private val placeholderPolicy: PlaceholderPolicy = PlaceholderPolicy.ALL,
    private val resolver: TypeResolver = TypeResolver.OWNER_FIRST,
) {

    private val nodesByKey = LinkedHashMap<String, Node>()
    private val childKeys = HashMap<String, MutableSet<String>>()
    private val refsByOwner = LinkedHashMap<String, OwnedRefs>()
    private val incomingByFqn = HashMap<String, MutableSet<String>>()
    private val definersByFqn = HashMap<String, MutableSet<String>>()
    private val typeKeysByFqn = HashMap<String, MutableSet<String>>()

    /** A scanned type's owning artifact plus its outgoing refs (targets are bare-FQN/member-key facts). */
    private class OwnedRefs(val artifactKey: String, val refs: List<Ref>)

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
     * Replaces the owned subgraph of [facts].fqn under [artifactKey]: removes the type's previous
     * outgoing refs, ensures the package path, upserts the type node, stores refs, and creates
     * placeholders for unscanned ref targets. [ClassFacts.members] are intentionally **not** materialized
     * — the in-memory graph is type-level; members are served on demand from the snapshot.
     */
    fun applyClass(artifactKey: String, facts: ClassFacts) {
        val ownerKey = typeKey(artifactKey, facts.fqn)
        removeOwned(ownerKey)
        val parentKey = ensurePackagePath(artifactKey, packageOf(facts.fqn))
        upsertType(artifactKey, facts.fqn, facts.kind, parentKey)
        storeRefs(ownerKey, artifactKey, facts.refs)
    }

    /**
     * Removes a scanned type's owned subgraph. If the FQN is still referenced and no other artifact
     * defines it, an external placeholder stands in; otherwise the node is removed outright.
     */
    fun removeClass(artifactKey: String, fqn: String) {
        val key = typeKey(artifactKey, fqn)
        removeOwned(key)
        val node = nodesByKey[key] ?: return
        removeNode(node)
        definersByFqn[fqn]?.let { it.remove(artifactKey); if (it.isEmpty()) definersByFqn.remove(fqn) }
        if (incomingByFqn[fqn]?.isNotEmpty() == true && definersByFqn[fqn].isNullOrEmpty()) ensurePlaceholder(fqn)
    }

    /**
     * Reconciles placeholders after a bulk load that added refs before all types were present: any ref
     * target without a scanned definer becomes a placeholder. Idempotent; the incremental [applyClass]
     * path already creates placeholders inline, so this is a no-op there.
     */
    fun resolvePlaceholders() {
        val targets = refsByOwner.values.asSequence().flatMap { it.refs }.map { typeOf(it.toKey) }.toCollection(HashSet())
        targets.forEach { if (definersByFqn[it].isNullOrEmpty()) ensurePlaceholder(it) }
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
     * Aggregated type-to-type dependencies as **FQN** endpoints, for diffing against the jQAssistant
     * projection and for display. Each ref target is resolved to a concrete type node first, then its FQN
     * is reported — so in the common (no duplicated FQN) case this is identical to grouping by target FQN.
     */
    fun typeDependencies(): Sequence<TypeDependency> =
        aggregatedEdges().map { TypeDependency(it.fromFqn, it.toFqn, it.weight, it.extends, it.implements, it.annotatedBy, it.other) }

    /**
     * The same aggregated dependencies as [typeDependencies], but with endpoints resolved to
     * artifact-scoped **node keys** ([TypeEdge]). This is what the hierarchical-graph bridge consumes so
     * edges connect the correct distinct nodes when an FQN is defined in more than one artifact.
     */
    fun typeDependencyEdges(): Sequence<TypeEdge> =
        aggregatedEdges().map { TypeEdge(it.fromKey, it.toKey, it.weight, it.extends, it.implements, it.annotatedBy, it.other) }

    // ── read access (mainly for tests and diagnostics) ─────────────────

    fun node(key: String): Node? = nodesByKey[key]
    /** All nodes (modules, packages, types, placeholders), in insertion order. */
    fun nodes(): Collection<Node> = nodesByKey.values
    fun contains(key: String): Boolean = key in nodesByKey
    fun childKeysOf(key: String): Set<String> = childKeys[key].orEmpty()
    fun nodeCount(): Int = nodesByKey.size
    fun incomingOwnersOf(fqn: String): Set<String> =
        incomingByFqn[fqn].orEmpty().mapNotNullTo(linkedSetOf()) { nodesByKey[it]?.fqn }

    /** The artifact-scoped key of a type node (`type:<artifact>:<fqn>`). */
    fun typeKey(artifactKey: String, fqn: String): String = "type:${artifactKey.length}:$artifactKey:$fqn"

    /** A type (or external-type placeholder) node by FQN; the first when an FQN is defined more than once. */
    fun typeByFqn(fqn: String): Node? = typeKeysByFqn[fqn]?.firstOrNull()?.let { nodesByKey[it] }

    /** Every type (or external-type placeholder) node with this FQN — more than one only across artifacts. */
    fun typesWithFqn(fqn: String): List<Node> = typeKeysByFqn[fqn].orEmpty().mapNotNull { nodesByKey[it] }

    // ── internals ──────────────────────────────────────────────────────

    private fun externalTypeKey(fqn: String) = typeKey(EXTERNAL_ARTIFACT_KEY, fqn)

    private fun isTypeNode(kind: Any): Boolean = JavaKinds.isTypeKind(kind) || kind == JavaKinds.EXTERNAL_TYPE

    private fun putNode(node: Node) {
        nodesByKey[node.key] = node
        node.parent?.let { childKeys.getOrPut(it) { linkedSetOf() } += node.key }
        if (isTypeNode(node.kind)) typeKeysByFqn.getOrPut(node.fqn) { linkedSetOf() } += node.key
    }

    /** Removes [node] from the key map, its parent's child set, and the by-FQN type index. */
    private fun removeNode(node: Node) {
        nodesByKey.remove(node.key)
        node.parent?.let { childKeys[it]?.remove(node.key) }
        if (isTypeNode(node.kind)) typeKeysByFqn[node.fqn]?.let { it.remove(node.key); if (it.isEmpty()) typeKeysByFqn.remove(node.fqn) }
    }

    /** Upserts a scanned type under [parentKey] and records its defining artifact. */
    private fun upsertType(artifactKey: String, fqn: String, kind: Any, parentKey: String) {
        val key = typeKey(artifactKey, fqn)
        val existing = nodesByKey[key]
        if (existing == null) {
            putNode(Node(key, kind, simpleName(fqn), fqn, parent = parentKey, placeholder = false))
        } else {
            reparent(key, existing.parent, parentKey)
            nodesByKey[key] = existing.copy(kind = kind, name = simpleName(fqn), parent = parentKey, placeholder = false)
        }
        definersByFqn.getOrPut(fqn) { mutableSetOf() } += artifactKey
        // Now that a real artifact defines this FQN, the external stand-in (if any) is redundant.
        removeExternalPlaceholder(fqn)
    }

    private fun removeExternalPlaceholder(fqn: String) {
        val node = nodesByKey[externalTypeKey(fqn)] ?: return
        if (node.placeholder) removeNode(node)
    }

    private fun ensurePlaceholder(fqn: String) {
        if (!definersByFqn[fqn].isNullOrEmpty()) return
        val key = externalTypeKey(fqn)
        if (key in nodesByKey) return
        if (!placeholderPolicy.shouldMaterialize(fqn)) return
        val pkgKey = ensurePackagePath(EXTERNAL_ARTIFACT_KEY, packageOf(fqn))
        putNode(Node(key, JavaKinds.EXTERNAL_TYPE, simpleName(fqn), fqn, parent = pkgKey, placeholder = true))
    }

    private fun storeRefs(ownerKey: String, artifactKey: String, refs: List<Ref>) {
        refsByOwner[ownerKey] = OwnedRefs(artifactKey, refs)
        for (r in refs) {
            val target = typeOf(r.toKey)
            if (definersByFqn[target].isNullOrEmpty()) ensurePlaceholder(target)
            incomingByFqn.getOrPut(target) { mutableSetOf() } += ownerKey
        }
    }

    /** Removes [ownerKey]'s children and outgoing refs (and their incoming bookkeeping); keeps the node. */
    private fun removeOwned(ownerKey: String) {
        childKeys.remove(ownerKey)?.toList()?.forEach { removeNodeCascade(it) }
        refsByOwner.remove(ownerKey)?.refs?.forEach { r ->
            val target = typeOf(r.toKey)
            incomingByFqn[target]?.let { owners ->
                owners.remove(ownerKey)
                if (owners.isEmpty()) incomingByFqn.remove(target)
            }
        }
    }

    private fun removeNodeCascade(key: String) {
        childKeys.remove(key)?.forEach { removeNodeCascade(it) }
        nodesByKey.remove(key)?.let { removeNode(it) }
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

    /** Aggregates each owner's refs by resolved target node, summing weights and collecting ref kinds. */
    private fun aggregatedEdges(): Sequence<Agg> =
        refsByOwner.asSequence().flatMap { (ownerKey, owned) ->
            val ownerFqn = nodesByKey[ownerKey]?.fqn ?: return@flatMap emptySequence<Agg>()
            owned.refs.groupBy { resolveTargetKey(owned.artifactKey, typeOf(it.toKey)) }
                .asSequence()
                .filter { (targetKey, _) -> targetKey != ownerKey }
                .map { (targetKey, rs) ->
                    val kinds = rs.mapTo(EnumSet.noneOf(RefKind::class.java)) { it.kind }
                    Agg(
                        fromKey = ownerKey,
                        toKey = targetKey,
                        fromFqn = ownerFqn,
                        toFqn = typeOf(rs.first().toKey),
                        weight = rs.sumOf { it.count ?: 1 },
                        extends = RefKind.EXTENDS in kinds,
                        implements = RefKind.IMPLEMENTS in kinds,
                        annotatedBy = RefKind.ANNOTATED_BY in kinds,
                        other = kinds.any { it !in RefKind.STRUCTURAL },
                    )
                }
        }

    /**
     * Resolves a reference's target FQN to a concrete type node key: the defining artifact chosen by
     * [resolver] (classpath shadowing) when the FQN is scanned, else the external placeholder key. The
     * returned key may name a node that was not materialized (placeholder policy excluded it); callers
     * that need a node treat a miss as "unresolved".
     */
    private fun resolveTargetKey(ownerArtifactKey: String, targetFqn: String): String {
        val definers = definersByFqn[targetFqn]
        return if (definers.isNullOrEmpty()) externalTypeKey(targetFqn)
        else typeKey(resolver.resolve(ownerArtifactKey, targetFqn, definers), targetFqn)
    }

    private class Agg(
        val fromKey: String,
        val toKey: String,
        val fromFqn: String,
        val toFqn: String,
        val weight: Int,
        val extends: Boolean,
        val implements: Boolean,
        val annotatedBy: Boolean,
        val other: Boolean,
    )

    private fun packageKey(artifactKey: String, pkgFqn: String) = "pkg:$artifactKey:$pkgFqn"
    private fun packageOf(fqn: String) = fqn.substringBeforeLast('.', "")
    private fun simpleName(fqn: String) = fqn.substringAfterLast('.')

    /** The owning type FQN of a ref endpoint: the part before '#' for a member key, else the key itself. */
    private fun typeOf(key: String) = key.substringBefore('#')

    companion object {
        const val EXTERNAL_ARTIFACT_KEY = "<external>"
        const val EXTERNAL_ARTIFACT_NAME = "External Types"
    }
}
