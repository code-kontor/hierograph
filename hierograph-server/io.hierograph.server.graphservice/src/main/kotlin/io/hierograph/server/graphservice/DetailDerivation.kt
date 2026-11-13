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
package io.hierograph.server.graphservice

import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.graphstore.jvm.archive.model.MemberFacts
import io.hierograph.graphstore.jvm.scanner.ScanResult

/**
 * The hierarchy-dependent member edges (overrides, virtual and resolved calls) of an assembled graph: the
 * edges no single scan can derive, computed with [HierarchyResolution]. Only the edges not already among
 * the direct detail edges are returned, so an edge that is also a direct one appears only once. The scanner
 * writes direct edges only, so every snapshot gains all its hierarchy-dependent edges here.
 *
 * The inputs are the graph's [GraphStore] for the type hierarchy, plus the declared members and the direct
 * detail edges, either read from a Parquet snapshot or taken straight from the scan ([fromScan]); both give
 * the same edges in the same order.
 */
internal object DetailDerivation {

    /** The order of the snapshot's `dependencies` table; the calls are resolved in it on either path. */
    private val SNAPSHOT_ORDER =
        compareBy<Dependency>({ it.fromType }, { it.fromKey }, { it.toType }, { it.toKey }, { it.kind })

    /**
     * Derive from a scan held in memory, without reading back the snapshot written from it. Takes what the
     * snapshot would hold: every scanned member, and the detail edges whose `from` type was scanned (the
     * snapshot files a detail edge under its `from` type's artifact and has no place for any other).
     */
    fun fromScan(store: GraphStore, scan: ScanResult): List<Dependency> {
        val scannedTypes = scan.classes.mapTo(HashSet()) { it.facts.fqn }
        return derive(
            store,
            scan.classes.flatMap { it.facts.members },
            scan.dependencies.filter { it.fromType in scannedTypes },
        )
    }

    /** Derive from the graph's [members] and direct [dependencies]. */
    fun derive(store: GraphStore, members: List<MemberFacts>, dependencies: List<Dependency>): List<Dependency> {
        val methodsByOwner = members
            .filter { it.kind == JavaNodeKind.METHOD || it.kind == JavaNodeKind.CONSTRUCTOR }
            .groupBy { it.key.substringBefore('#') }
        val superByFqn = store.typeDependencies()
            .filter { it.extends || it.implements }
            .groupBy({ it.fromFqn }, { it.toFqn })
        val types = store.nodes().asSequence()
            .filter { JavaNodeKind.isTypeKind(it.kind) }
            .map { n ->
                HierarchyResolution.Type(
                    fqn = n.fqn,
                    directSupertypes = superByFqn[n.fqn].orEmpty(),
                    methods = methodsByOwner[n.fqn].orEmpty().map { HierarchyResolution.Method(it.key, it.signature, it.modifiers, it.lineFrom, it.bridgeTarget) },
                    external = n.kind == JavaNodeKind.EXTERNAL_TYPE,
                )
            }.toList()
        // Stable sort: a snapshot's rows are already in this order, a scan's are put into it.
        val directCalls = dependencies.filter { it.kind == DependencyKind.CALLS }.sortedWith(SNAPSHOT_ORDER)
        val resolved = HierarchyResolution.resolve(types, directCalls)

        val existing = dependencies.mapTo(HashSet()) { Triple(it.fromKey, it.toKey, it.kind) }
        return resolved.filterNot { Triple(it.fromKey, it.toKey, it.kind) in existing }
    }
}
