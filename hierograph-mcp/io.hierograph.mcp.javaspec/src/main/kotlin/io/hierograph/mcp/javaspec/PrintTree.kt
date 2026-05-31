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
package io.hierograph.mcp.javaspec

import io.hierograph.hierarchicalgraph.core.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.core.model.HGNode

/**
 * Recursively prints this node and its descendants, one line per node, indented
 * by tree depth. Each line is `{indent*depth}[id] name (fqn)`, with the name
 * and `(fqn)` segments omitted when their value is unknown.
 *
 * [nameAndFqn] is called per node to extract its display name and fqn. The
 * default reads `properties["name"]` / `properties["fqn"]` from a
 * [DefaultNodeSource]; other source impls report `(null, null)` and the
 * extractor should be overridden by the caller (e.g. from a name/fqn map
 * captured elsewhere — see `JQAssistantHierarchyProvider.nameFqnByNodeId`).
 *
 * Output goes through [sink], defaulting to [println]. Override for tests or
 * for routing to a logger.
 */
fun HGNode.printTree(
    sink: (String) -> Unit = ::println,
    indent: String = "  ",
    nameAndFqn: (HGNode) -> Pair<String?, String?> = ::defaultNameAndFqn
) {
    printTree(this, depth = 0, indent = indent, sink = sink, nameAndFqn = nameAndFqn)
}

private fun printTree(
    node: HGNode,
    depth: Int,
    indent: String,
    sink: (String) -> Unit,
    nameAndFqn: (HGNode) -> Pair<String?, String?>
) {
    sink(formatLine(node, depth, indent, nameAndFqn(node)))
    for (child in node.children) {
        printTree(child, depth + 1, indent, sink, nameAndFqn)
    }
}

private fun formatLine(
    node: HGNode,
    depth: Int,
    indent: String,
    nameFqn: Pair<String?, String?>
): String {
    val (name, fqn) = nameFqn
    return buildString {
        repeat(depth) { append(indent) }
        append('[').append(node.identifier).append(']')
        if (name != null) {
            append(' ').append(name)
        }
        if (fqn != null) {
            append(" (").append(fqn).append(')')
        }
    }
}

private fun defaultNameAndFqn(node: HGNode): Pair<String?, String?> {
    val src = node.nodeSource
    if (src is DefaultNodeSource) {
        return src.properties["name"] to src.properties["fqn"]
    }
    return null to null
}
