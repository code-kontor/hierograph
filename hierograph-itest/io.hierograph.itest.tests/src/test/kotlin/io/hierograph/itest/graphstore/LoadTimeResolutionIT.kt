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

import io.hierograph.graphservice.HierarchyResolution
import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.model.DetailRef
import io.hierograph.graphstore.model.MemberModifiers
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.itest.fwk.SpringFixture
import io.hierograph.graphstore.model.JavaKinds
import io.hierograph.graphstore.model.JavaNodeKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The load-time hierarchy resolver ([HierarchyResolution]) exercised end-to-end over the real Spring
 * snapshot — the counterpart of the scanner's direct edges. Builds the same inputs the graph service does
 * (`deriveDetailRefs`) and asserts: `overrides` are derived (the scanner emits none), virtual/resolved
 * `calls` are produced, no edge targets a synthetic bridge method (bridge→real redirect), and the
 * virtual-dispatch fan does not explode (the external-placeholder fan-root guard).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoadTimeResolutionIT {

    private val dir = SpringFixture.directory
    private val reader = ParquetReader()
    private lateinit var directCalls: List<DetailRef>
    private lateinit var derived: List<DetailRef>
    private lateinit var bridgeKeys: Set<String>

    @BeforeAll
    fun resolve() {
        val store = GraphStore().also { ParquetReader().load(dir, it) }
        val members = reader.readAllMembers(dir)
        // Only bridges whose real target the scanner actually captured are redirectable; a handful of
        // bridges forward in a way the scanner can't resolve (null bridgeTarget) and legitimately remain.
        bridgeKeys = members.filter { MemberModifiers.isBridge(it.modifiers) && it.bridgeTarget != null }.map { it.key }.toHashSet()
        val methodsByOwner = members
            .filter { it.kind == JavaNodeKind.METHOD || it.kind == JavaNodeKind.CONSTRUCTOR }
            .groupBy { it.key.substringBefore('#') }
        val superByFqn = store.typeDependencies().filter { it.extends || it.implements }.groupBy({ it.from }, { it.to })
        val types = store.nodes().asSequence().filter { JavaKinds.isTypeKind(it.kind) }.map { n ->
            HierarchyResolution.Type(
                n.fqn,
                superByFqn[n.fqn].orEmpty(),
                methodsByOwner[n.fqn].orEmpty().map { HierarchyResolution.Method(it.key, it.signature, it.modifiers, it.lineFrom, it.bridgeTarget) },
                external = n.kind.toString() == JavaKinds.EXTERNAL_TYPE,
            )
        }.toList()
        directCalls = reader.readDetailRefs(dir, relationship = "calls")
        derived = HierarchyResolution.resolve(types, directCalls)
    }

    @Test
    fun `the scanner emits no overrides directly - they are derived at load`() {
        assertThat(reader.readDetailRefs(dir, relationship = "overrides")).isEmpty()
        assertThat(derived.count { it.relationship == "overrides" }).isGreaterThan(0)
    }

    @Test
    fun `virtual and resolved calls are derived`() {
        assertThat(derived.count { it.relationship == "calls" }).isGreaterThan(0)
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
        val derivedCalls = derived.count { it.relationship == "calls" }
        assertThat(derivedCalls).isLessThan(directCalls.size)
    }
}
