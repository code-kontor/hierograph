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
package io.hierograph.graphql.controller

import io.hierograph.graphql.HierarchicalGraphProvider
import io.hierograph.hierarchicalgraph.core.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HierarchyFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Covers the two AK-relevant behaviors of the cross-reference resolvers:
 * a container subject must resolve partners through its subtree (Bug 2), and a
 * multi-member subject must be able to exclude its own members as partners so
 * an ancestor-container partner shows a net (non-contaminated) weight.
 */
class NodeControllerCrossReferencesTest {

    private var nextId = 1L
    private val graph = HGGraphFactory.createHGGraph()

    private fun node(): HGNode =
        HGGraphFactory.createNode(graph) { DefaultNodeSource(identifier = nextId++) }

    private fun dependency(from: HGNode, to: HGNode, weight: Int) =
        HGGraphFactory.createCoreDependency(from, to, "USES") {
            DefaultDependencySource(identifier = nextId++)
        }.also { it.weight = weight }

    // Hierarchy:
    //   root
    //   ├── container (subject) ── containerChild
    //   ├── partnerParent ── partner                  (used-by direction)
    //   ├── partner2Parent ── partner2                 (uses direction)
    //   ├── membersAncestor ── member1, member2         (multi-member subject)
    //   ├── partnerRoot ── partnerChild                 (external partner for members)
    //   └── leafPartnerParent ── leafPartner             (leaf regression)
    private val root = node()
    private val container = node()
    private val containerChild = node()
    private val partnerParent = node()
    private val partner = node()
    private val partner2Parent = node()
    private val partner2 = node()
    private val membersAncestor = node()
    private val member1 = node()
    private val member2 = node()
    private val partnerRoot = node()
    private val partnerChild = node()
    private val leafSubject = node()
    private val leafPartnerParent = node()
    private val leafPartner = node()

    private val hierarchy = HierarchyFactory.createHierarchy(graph, root).also { h ->
        HierarchyFactory.addChild(h, root, container)
        HierarchyFactory.addChild(h, container, containerChild)
        HierarchyFactory.addChild(h, root, partnerParent)
        HierarchyFactory.addChild(h, partnerParent, partner)
        HierarchyFactory.addChild(h, root, partner2Parent)
        HierarchyFactory.addChild(h, partner2Parent, partner2)
        HierarchyFactory.addChild(h, root, membersAncestor)
        HierarchyFactory.addChild(h, membersAncestor, member1)
        HierarchyFactory.addChild(h, membersAncestor, member2)
        HierarchyFactory.addChild(h, root, partnerRoot)
        HierarchyFactory.addChild(h, partnerRoot, partnerChild)
        HierarchyFactory.addChild(h, root, leafSubject)
        HierarchyFactory.addChild(h, root, leafPartnerParent)
        HierarchyFactory.addChild(h, leafPartnerParent, leafPartner)
    }

    // Fall 1: container-subject, external partner references a child of the container.
    private val usedByEdge = dependency(from = partner, to = containerChild, weight = 3)
    private val usesEdge = dependency(from = containerChild, to = partner2, weight = 5)

    // Fall 2: multi-member subject with an internal edge (member1 -> member2) and an
    // external edge from a partner nested under partnerRoot.
    private val internalEdge = dependency(from = member1, to = member2, weight = 1)
    private val externalEdge = dependency(from = partnerChild, to = member1, weight = 1)

    // Fall 3: leaf subject regression.
    private val leafEdge = dependency(from = leafPartner, to = leafSubject, weight = 2)

    private val controller = NodeController(HierarchicalGraphProvider { hierarchy })

    private fun ids(vararg nodes: HGNode): List<String> = nodes.map { it.identifier.toString() }

    @Test
    fun `container subject - childrenFilteredByReferencedNodes resolves the partner via the container's subtree`() {
        val result =
            controller.childrenFilteredByReferencedNodes(partnerParent, ids(container), excludingNodeIds = null)
        assertThat(result.nodeList.map { it.identifier }).containsExactly(partner.identifier)
    }

    @Test
    fun `container subject - dependenciesTo returns the edge with the correct weight`() {
        val result = controller.dependenciesTo(partner, ids(container), excludingNodeIds = null)
        assertThat(result).containsExactly(usedByEdge)
        assertThat(result.sumOf { it.weight }).isEqualTo(3)
    }

    @Test
    fun `container subject - childrenFilteredByReferencingNodes resolves the partner via the container's subtree (mirror)`() {
        val result =
            controller.childrenFilteredByReferencingNodes(partner2Parent, ids(container), excludingNodeIds = null)
        assertThat(result.nodeList.map { it.identifier }).containsExactly(partner2.identifier)
    }

    @Test
    fun `container subject - dependenciesFrom returns the edge with the correct weight (mirror)`() {
        val result = controller.dependenciesFrom(partner2, ids(container), excludingNodeIds = null)
        assertThat(result).containsExactly(usesEdge)
        assertThat(result.sumOf { it.weight }).isEqualTo(5)
    }

    @Test
    fun `multi-member subject - without excludingNodeIds the internal edge contaminates the aggregate`() {
        val result = controller.dependenciesTo(root, ids(member1, member2), excludingNodeIds = null)
        assertThat(result).containsExactlyInAnyOrder(internalEdge, externalEdge)
        assertThat(result.sumOf { it.weight }).isEqualTo(2)
    }

    @Test
    fun `multi-member subject - excludingNodeIds strips the internal edge and leaves the net weight`() {
        val result = controller.dependenciesTo(root, ids(member1, member2), excludingNodeIds = ids(member1, member2))
        assertThat(result).containsExactly(externalEdge)
        assertThat(result.sumOf { it.weight }).isEqualTo(1)
    }

    @Test
    fun `leaf subject - null and empty excludingNodeIds yield the identical unchanged result`() {
        val withNull = controller.childrenFilteredByReferencedNodes(leafPartnerParent, ids(leafSubject), null)
        val withEmpty = controller.childrenFilteredByReferencedNodes(leafPartnerParent, ids(leafSubject), emptyList())
        assertThat(withNull.nodeList.map { it.identifier }).containsExactly(leafPartner.identifier)
        assertThat(withEmpty.nodeList.map { it.identifier }).containsExactly(leafPartner.identifier)
    }
}
