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

import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.NodeMetadata
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.server.graphservice.HierarchicalGraphService
import java.nio.file.Path

/**
 * Shared base for the detail-level MCP tools (type_details, method_details, field_details) and the
 * detail-level engine behind outgoing_dependencies / incoming_dependencies with detailLevel "detail".
 * Holds the slim-node and primitive-ref helpers used by 2+ of these so each concrete class can focus on
 * its own logic.
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

    /**
     * The Parquet detail directory for the live graph, or `null` when the detail snapshot is still being
     * written after a `manifest`-mode rescan. Detail engines must treat `null` as [detailRebuilding] and
     * never read a previous directory — so a query during the write window gets no *stale* data.
     */
    protected fun readyDetailDir(): Path? = graphService.readyDetailDir()

    /**
     * A structured error in the shape every tool uses: `{"error": {"code", "message", <extras>, "recovery"}}`.
     * [extras] carry the fields that help the caller recover (e.g. `actual_kind`).
     */
    protected fun errorResponse(
        code: String,
        message: String,
        recovery: String,
        extras: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> = mapOf(
        "error" to linkedMapOf<String, Any?>("code" to code, "message" to message).apply {
            putAll(extras)
            put("recovery", recovery)
        }
    )

    /** Uniform error returned when detail is queried before its snapshot for the live graph is ready. */
    protected fun detailRebuilding(): Map<String, Any?> = errorResponse(
        "DETAIL_REBUILDING",
        "Detail data is being rebuilt after a rescan.",
        "Retry the call in a moment.",
    )

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
