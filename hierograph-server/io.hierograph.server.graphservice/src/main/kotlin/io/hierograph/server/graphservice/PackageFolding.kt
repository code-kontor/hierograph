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

import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.algorithms.ChainFolding
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.NodeMetadata

/**
 * The server's chain folding (hierarchical-graph algorithms specification, §8): a chain of lone packages, such as
 * `io` › `hierograph` › `server` holding nothing but each other, is shown as its last package. Packages fold
 * wherever they are, External Types' included; nothing else does, so a chain never crosses a module or a view
 * group. Every tree the GraphQL API and the MCP tools list folds by this one rule, computed on demand in the
 * request's hierarchy (a view's, after its exclusions and moves).
 */
object PackageFolding {

    /** The folding of [hierarchy]: packages are its foldable nodes. */
    fun of(hierarchy: Hierarchy): ChainFolding = ChainFolding(hierarchy) { it.kind == JavaNodeKind.PACKAGE }

    /**
     * How a node is named where it is displayed: a folded node by its qualified name relative to the package it is
     * displayed under (`server.mcp` under `io.hierograph`), or in full under anything else (`io.hierograph.server`
     * under a module); a node that isn't folded by its own name.
     */
    fun label(node: HGNode, folding: ChainFolding): String {
        if (folding.skippedAbove(node).isEmpty()) return NodeMetadata.getName(node)
        val name = NodeMetadata.getQualifiedName(node)
        val parent = folding.displayParentOf(node)
        if (parent == null || parent.kind != JavaNodeKind.PACKAGE) return name
        val prefix = NodeMetadata.getQualifiedName(parent) + "."
        return if (name.startsWith(prefix)) name.removePrefix(prefix) else name
    }
}
