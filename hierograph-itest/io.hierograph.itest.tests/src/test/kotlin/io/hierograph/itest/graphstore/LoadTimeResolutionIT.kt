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
package io.hierograph.itest.graphstore

import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.server.graphservice.HierarchyResolution
import io.hierograph.graphstore.jvm.GraphStore
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.model.MemberModifiers
import io.hierograph.graphstore.jvm.archive.ParquetReader
import io.hierograph.graphstore.jvm.SnapshotLoader
import io.hierograph.itest.fwk.SpringFixture
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The load-time hierarchy resolver ([HierarchyResolution]) exercised end-to-end over the real Spring
 * snapshot — the counterpart of the scanner's direct edges. Builds the same inputs the graph service does
 * (`DetailDerivation.derive`) and asserts: `overrides` are derived (the scanner emits none), virtual/resolved
 * `calls` are produced, no edge targets a synthetic bridge method (bridge→real redirect), and the
 * virtual-dispatch fan does not explode (the external-placeholder fan-root guard).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoadTimeResolutionIT {

    private val dir = SpringFixture.directory
    private val reader = ParquetReader()
    private lateinit var directCalls: List<Dependency>
    private lateinit var derived: List<Dependency>
    private lateinit var bridgeKeys: Set<String>

    @BeforeAll
    fun resolve() {
        val store = GraphStore().also { SnapshotLoader().load(dir, it) }
        val members = reader.readAllMembers(dir)
        // Only bridges whose real target the scanner actually captured are redirectable; a handful of
        // bridges forward in a way the scanner can't resolve (null bridgeTarget) and legitimately remain.
        bridgeKeys = members.filter { (it.modifiers and MemberModifiers.BRIDGE != 0) && it.bridgeTarget != null }.map { it.key }.toHashSet()
        val methodsByOwner = members
            .filter { it.kind == JavaNodeKind.METHOD || it.kind == JavaNodeKind.CONSTRUCTOR }
            .groupBy { it.key.substringBefore('#') }
        val superByFqn = store.typeDependencies().filter { it.extends || it.implements }.groupBy({ it.fromFqn }, { it.toFqn })
        val types = store.nodes().asSequence().filter { JavaNodeKind.isTypeKind(it.kind) }.map { n ->
            HierarchyResolution.Type(
                n.fqn,
                superByFqn[n.fqn].orEmpty(),
                methodsByOwner[n.fqn].orEmpty().map { HierarchyResolution.Method(it.key, it.signature, it.modifiers, it.lineFrom, it.bridgeTarget) },
                external = n.kind.toString() == JavaNodeKind.EXTERNAL_TYPE,
            )
        }.toList()
        directCalls = reader.readDependencies(dir, kind = DependencyKind.CALLS)
        derived = HierarchyResolution.resolve(types, directCalls)
    }

    @Test
    fun `the scanner emits no overrides directly - they are derived at load`() {
        assertThat(reader.readDependencies(dir, kind = DependencyKind.OVERRIDES)).isEmpty()
        assertThat(derived.count { it.kind == DependencyKind.OVERRIDES }).isGreaterThan(0)
    }

    @Test
    fun `virtual and resolved calls are derived`() {
        assertThat(derived.count { it.kind == DependencyKind.CALLS }).isGreaterThan(0)
    }

    @Test
    fun `no derived edge targets a synthetic bridge method that has a resolved real target`() {
        assertThat(bridgeKeys).describedAs("redirectable bridge methods").isNotEmpty // the fixture has covariant/generic bridges
        assertThat(derived.none { it.toKey in bridgeKeys }).isTrue()
    }

    @Test
    fun `the virtual-dispatch fan does not explode through external placeholder types`() {
        // Before the external-fan fix, fanning calls to JDK interfaces (java.util.Iterator#hasNext, ...)
        // inflated the derived set far past the direct-call set. The derived virtual/resolved calls must
        // stay well under the direct `calls` count.
        val derivedCalls = derived.count { it.kind == DependencyKind.CALLS }
        assertThat(derivedCalls).isLessThan(directCalls.size)
    }
}
