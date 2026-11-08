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
package io.hierograph.graphstore.jvm

import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.Node
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.model.RefKind
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.JavaNodeKind
import java.util.EnumSet

/**
 * Hierograph's in-memory structural model, built directly from [ClassFacts] during ingest: the
 * hierarchy of modules, packages, types and members, plus the type-to-type refs between them. The
 * hierarchy and type-dependency projections are what the downstream layers (hierarchical graph, detail
 * scoping, load-time resolution) consume. See `spec/hierograph-graphstore.md`.
 *
 * **Identity is artifact-scoped.** A type node's [Node.key] is `type:<n>:<artifact>:<fqn>` (see
 * [typeKey]; `<n>` is the artifact key's length, which keeps the key unambiguous although a GAV contains
 * `:`), not the bare FQN, because the same fully-qualified name can be defined in more than one artifact
 * (shaded jars, duplicate classes); each becomes a distinct node under its own artifact. Packages
 * (`pkg:<artifact>:<fqn>`) and modules (the artifact key) are artifact-scoped too. A reference, however,
 * carries only a bare FQN, so it is **resolved** to a concrete type node via [resolver] (classpath
 * shadowing; see [TypeResolver]) when dependencies are read — the two query shapes expose this both ways:
 * [typeDependencies] projects FQN endpoints (for display and name-level reasoning), [typeDependencyEdges]
 * the resolved artifact-scoped node keys (for the hierarchical graph).
 *
 * Updates replace a type's *owned subgraph* (members + outgoing refs). Removing a type that is still
 * referenced, and defined by no other artifact, leaves a placeholder in its place so references from
 * unchanged classes stay valid.
 *
 * Members are materialized as children of their declaring type (`member:<n>:<artifact>:<owner fqn>#<signature>`,
 * see [memberKey]) for navigation; their *dependencies* are not — member-level refs stay in the snapshot
 * as a strictly on-demand (detail) concern. [placeholderPolicy] decides which unscanned ref targets
 * become in-memory placeholder nodes; it is a projection policy only and never affects what the snapshot
 * persists.
 *
 * Not thread-safe: build a store on one thread, then publish it and treat it as read-only.
 */
class GraphStore(
    private val placeholderPolicy: PlaceholderPolicy = PlaceholderPolicy.ALL,
    private var resolver: TypeResolver = TypeResolver.OWNER_FIRST,
) {

    /**
     * Installs the type-shadowing [resolver] that binds ref targets to defining artifacts. It is consulted
     * when dependencies are read ([typeDependencies] / [typeDependencyEdges]), so a change takes effect at
     * the next query; by convention it is installed before ingest. `ParquetReader.load` uses it to replace
     * [TypeResolver.OWNER_FIRST] with a classpath-order resolver built from a snapshot's `classpath.parquet`.
     */
    fun setTypeResolver(resolver: TypeResolver) {
        this.resolver = resolver
    }

    private val nodesByKey = LinkedHashMap<String, Node>()
    private val childKeys = HashMap<String, MutableSet<String>>()
    private val refsByOwner = LinkedHashMap<String, OwnedRefs>()
    private val incomingByFqn = HashMap<String, MutableSet<String>>()
    private val definersByFqn = HashMap<String, MutableSet<String>>()
    private val typeKeysByFqn = HashMap<String, MutableSet<String>>()
    private val nodeKeysByMemberKey = HashMap<String, MutableSet<String>>()

    /** A scanned type's owning artifact plus its outgoing refs (targets are bare-FQN/member-key facts). */
    private class OwnedRefs(val artifactKey: String, val refs: List<Ref>)

    init {
        // Synthetic module owning every external-type placeholder.
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
     * members and outgoing refs, ensures the package path, upserts the type node, materializes
     * [ClassFacts.members] as its children, stores refs, and creates placeholders for unscanned ref
     * targets. Member-level refs are not part of [ClassFacts] and stay in the snapshot.
     */
    fun applyClass(artifactKey: String, facts: ClassFacts) {
        val ownerKey = typeKey(artifactKey, facts.fqn)
        removeOwned(ownerKey)
        val parentKey = ensurePackagePath(artifactKey, packageOf(facts.fqn))
        upsertType(artifactKey, facts.fqn, facts.kind, parentKey)
        for (m in facts.members) {
            // Constructors are java.method nodes, as in the hierarchy vocabulary ("includes constructors").
            val kind = if (m.kind == JavaNodeKind.CONSTRUCTOR) JavaKinds.METHOD else m.kind
            putNode(Node(memberKey(artifactKey, m.key), kind, m.signature, m.key, parent = ownerKey))
        }
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
     * Aggregated type-to-type dependencies as **FQN** endpoints, for display and name-level reasoning
     * (including targets the placeholder policy keeps out of the graph). Each ref target is resolved to a concrete type node first, then its FQN
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

    /** The artifact-scoped key of a member node (`member:<artifact>:<owner fqn>#<signature>`). */
    fun memberKey(artifactKey: String, memberKey: String): String = "member:${artifactKey.length}:$artifactKey:$memberKey"

    /**
     * The member node for a bare member key (`<owner fqn>#<signature>`, as detail refs carry it), declared
     * by the owner [typeByFqn] resolves to — so member and owner handles agree. `null` when the owner is
     * not scanned (e.g. a member of an external type) or does not declare it.
     */
    fun memberByKey(memberKey: String): Node? {
        val owner = typeByFqn(typeOf(memberKey)) ?: return null
        return nodeKeysByMemberKey[memberKey].orEmpty().firstNotNullOfOrNull { k -> nodesByKey[k]?.takeIf { it.parent == owner.key } }
    }

    // ── internals ──────────────────────────────────────────────────────

    private fun externalTypeKey(fqn: String) = typeKey(EXTERNAL_ARTIFACT_KEY, fqn)

    private fun isTypeNode(kind: Any): Boolean = JavaKinds.isTypeKind(kind) || kind == JavaKinds.EXTERNAL_TYPE

    private fun isMemberNode(kind: Any): Boolean = kind in JavaKinds.MEMBER_KINDS

    private fun putNode(node: Node) {
        nodesByKey[node.key] = node
        node.parent?.let { childKeys.getOrPut(it) { linkedSetOf() } += node.key }
        if (isTypeNode(node.kind)) typeKeysByFqn.getOrPut(node.fqn) { linkedSetOf() } += node.key
        if (isMemberNode(node.kind)) nodeKeysByMemberKey.getOrPut(node.fqn) { linkedSetOf() } += node.key
    }

    /** Removes [node] from the key map, its parent's child set, and the by-FQN type / by-key member index. */
    private fun removeNode(node: Node) {
        nodesByKey.remove(node.key)
        node.parent?.let { childKeys[it]?.remove(node.key) }
        if (isTypeNode(node.kind)) typeKeysByFqn[node.fqn]?.let { it.remove(node.key); if (it.isEmpty()) typeKeysByFqn.remove(node.fqn) }
        if (isMemberNode(node.kind)) nodeKeysByMemberKey[node.fqn]?.let { it.remove(node.key); if (it.isEmpty()) nodeKeysByMemberKey.remove(node.fqn) }
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
        // Now that a real artifact defines this FQN, the external placeholder (if any) is redundant.
        removeExternalPlaceholder(fqn)
    }

    private fun removeExternalPlaceholder(fqn: String) {
        val node = nodesByKey[externalTypeKey(fqn)] ?: return
        if (!node.placeholder) return
        removeNode(node)
        pruneEmptyPackages(node.parent)
    }

    /**
     * Removes [key] and its ancestor packages under the external module while they have no children
     * left, so a placeholder superseded by a scanned type doesn't leave an empty package chain behind.
     */
    private fun pruneEmptyPackages(key: String?) {
        var k = key
        while (k != null && k != EXTERNAL_ARTIFACT_KEY && childKeys[k].isNullOrEmpty()) {
            val pkg = nodesByKey[k] ?: return
            childKeys.remove(k)
            removeNode(pkg)
            k = pkg.parent
        }
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
