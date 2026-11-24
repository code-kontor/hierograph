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
package io.hierograph.mcp.server.tools

import io.hierograph.hierarchicalgraph.algorithms.ChainFolding
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.server.graphservice.PackageFolding

/**
 * Chain folding in the tools that list a tree (`list_children`, `list_descendants`, `levelized_structure`):
 * a chain of lone packages is listed as its last package, which names the packages it stands for. Fewer
 * entries and fewer calls for the same information; `compact: false` lists the tree as it is.
 */
object CompactTree {

    /** The `compact` parameter's description, the same in every tool. */
    const val PARAM_DESCRIPTION =
        "Fold chains of lone packages (default true): a package that holds nothing but one package, and so on " +
            "down, is listed as the chain's last package, named in full and with the skipped packages in " +
            "compacted_from. The skipped packages are real nodes: find_node finds them, and their ids work " +
            "everywhere. Pass false to list the tree as it is."

    /** The folding to list with: `null` when [compact] is false. */
    fun folding(compact: Boolean?, hierarchy: Hierarchy): ChainFolding? =
        if (compact == false) null else PackageFolding.of(hierarchy)

    /**
     * Marks [ref] as a folded entry when [node] stands for skipped packages: its `name` becomes the name the folded
     * tree shows (`io.hierograph.server` under a module), and `compacted_from` lists the qualified names of the
     * skipped packages, outermost first. Nothing for a node that isn't folded.
     */
    fun mark(ref: MutableMap<String, Any?>, node: HGNode, folding: ChainFolding?) {
        if (folding == null) return
        val skipped = folding.skippedAbove(node)
        if (skipped.isEmpty()) return
        ref["name"] = PackageFolding.label(node, folding)
        ref["compacted_from"] = skipped.map { NodeMetadata.getQualifiedName(it) }
    }

    /** The name a listing filters by: the folded name for a folded node, its own name otherwise. */
    fun name(node: HGNode, folding: ChainFolding?): String =
        if (folding == null) NodeMetadata.getName(node) else PackageFolding.label(node, folding)
}
