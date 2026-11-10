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
package io.hierograph.itest.hierarchicalgraph

import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.algorithms.GraphUtils
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.hierarchicalgraph.model.SyntheticNodeSource
import io.hierograph.itest.fwk.AbstractHierarchicalGraphIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Forks, local nodes and `move` on the real Spring 7.0.8 model: the two scenarios views are built on.
 *
 * - **Grouping**: the Spring jars grouped into Core Container, Data Access and Web, as local nodes in a
 *   fork of the served hierarchy. The group-level results must equal the sums over the members in the
 *   base hierarchy.
 * - **Restructuring**: `org.springframework.core.io` moved out of `spring-core` into a new local module.
 *   The new module's dependencies must equal the package's own, and the split must show up as a cycle.
 *
 * Every test works on a fork; the served hierarchy is shared with the other integration tests and must stay
 * as scanned, which each test checks as well.
 */
class ForkScenarioIT : AbstractHierarchicalGraphIntegrationTest() {

    private val nextLocalId = AtomicLong(-1_000_000)

    private val base: Hierarchy get() = model.hierarchy

    private fun jar(name: String) = "$name-7.0.8.jar"

    private fun Hierarchy.module(name: String): HGNode =
        childrenOf(rootNode).single { NodeMetadata.getName(it) == jar(name) }

    private fun Hierarchy.localNode(kind: Any, name: String, parent: HGNode = rootNode): HGNode =
        createLocalNode(kind) { SyntheticNodeSource(nextLocalId.getAndDecrement(), name, "view:$name") }
            .also { addChild(parent, it) }

    /** A multiset of dependencies: AssertJ's any-order list comparison is quadratic on lists this large. */
    private fun <T> bag(items: List<T>): Map<T, Int> = items.groupingBy { it }.eachCount()

    private val groups = linkedMapOf(
        "core-container" to listOf("spring-core", "spring-beans", "spring-context", "spring-expression", "spring-aop"),
        "data-access" to listOf("spring-jdbc", "spring-tx", "spring-orm", "spring-r2dbc", "spring-oxm", "spring-jms"),
        "web" to listOf("spring-web", "spring-webmvc", "spring-webflux", "spring-websocket"),
    )

    /** A fork of the served hierarchy with [groups] applied; returns the fork and `group name → node`. */
    private fun grouped(): Pair<Hierarchy, Map<String, HGNode>> {
        val fork = base.fork()
        val nodes = groups.mapValues { (name, members) ->
            fork.localNode("view.group", name).also { g -> members.forEach { fork.move(fork.module(it), g) } }
        }
        return fork to nodes
    }

    private fun assertBaseUnchanged() {
        val top = base.childrenOf(base.rootNode)
        assertThat(top).hasSize(22)
        assertThat(top.map { NodeMetadata.getKind(it) }).containsOnly(JavaKinds.MODULE.value)
        assertThat(base.localNodes).isEmpty()
        assertThat(base.descendantsOf(base.module("spring-core"))).hasSize(13_824)
    }

    @Test
    fun `grouping the jars puts the groups and the ungrouped jars at the top level`() {
        val (fork, groupNodes) = grouped()

        val top = fork.childrenOf(fork.rootNode)
        assertThat(top).hasSize(22 - groups.values.sumOf { it.size } + groups.size)
        assertThat(top).containsAll(groupNodes.values)
        for ((name, members) in groups) {
            assertThat(fork.childrenOf(groupNodes.getValue(name)).map { NodeMetadata.getName(it) })
                .containsExactlyElementsOf(members.map { jar(it) })
        }
        assertThat(fork.descendantsOf(fork.rootNode)).hasSize(base.descendantsOf(base.rootNode).size + groups.size)
        assertBaseUnchanged()
    }

    @Test
    fun `a group accumulates exactly the dependencies of its member jars`() {
        val (fork, groupNodes) = grouped()

        for ((name, members) in groups) {
            val group = groupNodes.getValue(name)
            val expectedOut = members.flatMap { base.accumulatedOutgoing(base.module(it)) }
            val expectedIn = members.flatMap { base.accumulatedIncoming(base.module(it)) }
            assertThat(bag(fork.accumulatedOutgoing(group))).`as`(name).isEqualTo(bag(expectedOut))
            assertThat(bag(fork.accumulatedIncoming(group))).`as`(name).isEqualTo(bag(expectedIn))
        }
        assertBaseUnchanged()
    }

    @Test
    fun `group-to-group weights are the sums of the member-to-member weights in the base hierarchy`() {
        val (fork, groupNodes) = grouped()
        val names = groups.keys.toList()

        val dsm = GraphUtils.computeAdjacencyMatrix(names.map { groupNodes.getValue(it) }, fork)

        for ((i, from) in names.withIndex()) for ((j, to) in names.withIndex()) {
            if (i == j) continue
            val expected = groups.getValue(from).sumOf { m1 ->
                groups.getValue(to).sumOf { m2 ->
                    base.getAggregatedDependency(base.module(m1), base.module(m2))?.aggregatedWeight ?: 0
                }
            }
            val aggregated = fork.getAggregatedDependency(groupNodes.getValue(from), groupNodes.getValue(to))
            assertThat(aggregated?.aggregatedWeight ?: 0).`as`("$from → $to").isEqualTo(expected)
            assertThat(dsm[i][j]).`as`("DSM $from → $to").isEqualTo(expected)
        }
        // the well-known direction of the Spring layering
        assertThat(fork.getAggregatedDependency(groupNodes.getValue("web"), groupNodes.getValue("core-container"))).isNotNull
        assertThat(fork.getAggregatedDependency(groupNodes.getValue("data-access"), groupNodes.getValue("core-container"))).isNotNull
        assertBaseUnchanged()
    }

    @Test
    fun `a real node keeps its identity in the fork`() {
        val (fork, groupNodes) = grouped()
        val resource = base.descendantsOf(base.module("spring-core"))
            .single { NodeMetadata.getQualifiedName(it) == "org.springframework.core.io.Resource" }

        assertThat(fork.lookupNode(resource.identifier)).isSameAs(resource)
        assertThat(fork.predecessorsOf(resource)).contains(base.module("spring-core"), groupNodes.getValue("core-container"))
        assertThat(base.predecessorsOf(resource)).doesNotContain(groupNodes.getValue("core-container"))
        assertThat(base.lookupNode(groupNodes.getValue("core-container").identifier)).isNull()
    }

    @Test
    fun `moving a package into a new module moves its subtree and its dependencies`() {
        val springCore = base.module("spring-core")
        val coreIo = base.descendantsOf(springCore, JavaNodeKind.PACKAGE)
            .single { NodeMetadata.getQualifiedName(it) == "org.springframework.core.io" }
        val ioSubtree = (base.descendantsOf(coreIo) + coreIo).toSet()
        val restOfCore = base.descendantsOf(springCore).toSet() - ioSubtree
        // what the package exchanges with the rest of spring-core, computed in the base hierarchy
        val ioToRest = base.accumulatedOutgoing(coreIo).filter { it.to in restOfCore }.sumOf { it.weight }
        val restToIo = base.accumulatedIncoming(coreIo).filter { it.from in restOfCore }.sumOf { it.weight }
        val originalParent = base.parentOf(coreIo)

        val fork = base.fork()
        val coreIoModule = fork.localNode(JavaNodeKind.MODULE, "spring-core-io")
        fork.move(coreIo, coreIoModule)

        // the subtree moved, the rest of spring-core stayed
        assertThat(fork.descendantsOf(coreIoModule)).hasSize(ioSubtree.size)
        assertThat(fork.descendantsOf(springCore)).hasSize(13_824 - ioSubtree.size)
        assertThat(fork.isPredecessorOf(springCore, coreIo)).isFalse()
        // the new module carries the package's dependencies, nothing more
        assertThat(bag(fork.accumulatedOutgoing(coreIoModule))).isEqualTo(bag(base.accumulatedOutgoing(coreIo)))
        assertThat(bag(fork.accumulatedIncoming(coreIoModule))).isEqualTo(bag(base.accumulatedIncoming(coreIo)))
        // and what used to be internal to spring-core now crosses the new module boundary
        assertThat(ioToRest).isPositive()
        assertThat(restToIo).isPositive()
        assertThat(fork.getAggregatedDependency(coreIoModule, springCore)!!.aggregatedWeight).isEqualTo(ioToRest)
        assertThat(fork.getAggregatedDependency(springCore, coreIoModule)!!.aggregatedWeight).isEqualTo(restToIo)
        // so the extraction as drafted creates a module cycle
        val cycles = GraphUtils.detectCycles(fork.childrenOf(fork.rootNode), fork)
        assertThat(cycles).anySatisfy { assertThat(it).contains(springCore, coreIoModule) }

        assertThat(base.parentOf(coreIo)).isSameAs(originalParent)
        assertBaseUnchanged()
    }

    @Test
    fun `the module level of the base hierarchy is unchanged by restructuring a fork`() {
        val top = base.childrenOf(base.rootNode)
        val before = GraphUtils.computeAdjacencyMatrix(top, base).map { it.toList() }

        val fork = base.fork()
        val coreIo = fork.descendantsOf(fork.module("spring-core"), JavaNodeKind.PACKAGE)
            .single { NodeMetadata.getQualifiedName(it) == "org.springframework.core.io" }
        fork.move(coreIo, fork.localNode(JavaNodeKind.MODULE, "spring-core-io"))
        grouped()

        assertThat(GraphUtils.computeAdjacencyMatrix(top, base).map { it.toList() }).isEqualTo(before)
        assertBaseUnchanged()
    }
}
