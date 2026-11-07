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
package io.hierograph.mcp.server.tools.detail

import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.NodeMetadata
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.graphservice.HierarchicalGraphService

/**
 * Shared base for the detail-level MCP tools (method_details, field_details, type_details,
 * detail_dependencies). Holds the slim-node and primitive-ref helpers used by 2+ of these tools so each
 * concrete tool class can focus on its own logic.
 *
 * This class is intentionally not a Spring `@Component`; only the concrete subclasses are.
 */
abstract class AbstractDetailTool(
    protected val graphService: HierarchicalGraphService
) {

    protected fun putSlimNode(nodes: MutableMap<String, Any>, id: Long, name: String?, fqn: String?, kind: String?) {
        val key = id.toString()
        if (nodes.containsKey(key)) return
        nodes[key] = linkedMapOf(
            "name" to (name ?: ""),
            "qualified_name" to (fqn ?: ""),
            "kind" to (kind ?: "unknown")
        )
    }

    protected fun putSlimNode(nodes: MutableMap<String, Any>, node: HGNode) {
        val id = (node.identifier as? Number)?.toLong() ?: 0L
        putSlimNode(nodes, id, NodeMetadata.getName(node), NodeMetadata.getQualifiedName(node), NodeMetadata.getKind(node))
    }

    /** Inline NodeRef used by single-entity tools (e.g. method_details) for primitive type slots. */
    protected fun primitiveRef(name: String): Map<String, Any?> = linkedMapOf(
        "id" to null,
        "name" to name,
        "qualified_name" to name,
        "kind" to JavaKinds.PRIMITIVE.value
    )

    companion object {
        @JvmStatic
        protected val JAVA_PRIMITIVES: Set<String> = JavaKinds.JAVA_PRIMITIVES
    }
}
