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
package io.hierograph.mcp.server.core

import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantNodeMetadataProvider

/**
 * Dumps a node and its descendants as an indented tree, one line per node.
 *
 * Each line is `{indent*depth}[id] name (fqn)`, with the `name` and `(fqn)`
 * segments omitted when their value is unknown. This mirrors the output of
 * `io.hierograph.mcp.javaspec.printTree`, but resolves name/fqn through the
 * server's [JQAssistantNodeMetadataProvider] (i.e. from the
 * `ExtendedGraphDbNodeSource` attached to each node).
 *
 * Output goes through [sink], defaulting to [println]. Override [nameAndFqn]
 * to source display fields from elsewhere, or [sink] to route to a logger or
 * collect into a buffer (see [dumpToString]).
 */
object TreeTraverser {

    fun dumpTree(
        node: HGNode,
        sink: (String) -> Unit = ::println,
        indent: String = "  ",
        nameAndFqn: (HGNode) -> Pair<String?, String?> = ::defaultNameAndFqn
    ) {
        dump(node, depth = 0, indent = indent, sink = sink, nameAndFqn = nameAndFqn)
    }

    /**
     * Renders the tree rooted at [node] into a single newline-separated string.
     */
    fun dumpToString(
        node: HGNode,
        indent: String = "  ",
        nameAndFqn: (HGNode) -> Pair<String?, String?> = ::defaultNameAndFqn
    ): String = buildString {
        dumpTree(node, sink = { appendLine(it) }, indent = indent, nameAndFqn = nameAndFqn)
    }.trimEnd('\n')

    private fun dump(
        node: HGNode,
        depth: Int,
        indent: String,
        sink: (String) -> Unit,
        nameAndFqn: (HGNode) -> Pair<String?, String?>
    ) {
        sink(formatLine(node, depth, indent, nameAndFqn(node)))
        for (child in node.children) {
            dump(child, depth + 1, indent, sink, nameAndFqn)
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
        val name = JQAssistantNodeMetadataProvider.getName(node).ifEmpty { null }
        val fqn = JQAssistantNodeMetadataProvider.getQualifiedName(node).ifEmpty { null }
        return name to fqn
    }
}
