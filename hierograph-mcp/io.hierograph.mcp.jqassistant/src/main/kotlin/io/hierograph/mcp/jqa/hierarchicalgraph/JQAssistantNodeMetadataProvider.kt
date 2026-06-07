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
package io.hierograph.mcp.jqa.hierarchicalgraph

import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbNodeSource

object JQAssistantNodeMetadataProvider {

    fun getName(node: HGNode): String {
        val src = node.nodeSource as? ExtendedGraphDbNodeSource ?: return ""
        return src.name
    }

    fun getQualifiedName(node: HGNode): String {
        val src = node.nodeSource as? ExtendedGraphDbNodeSource ?: return ""
        return src.fqn
    }

    fun getKind(node: HGNode): String {
        val src = node.nodeSource as? GraphDbNodeSource ?: return "Unknown"
        return getKindFromLabels(src.labels.toList())
    }

    fun getKindFromLabels(labels: List<String>): String {
        for (candidate in DEFAULT_KNOWN_KINDS) {
            if (candidate in labels) return candidate
        }
        return if (labels.isEmpty()) "Unknown" else labels[0]
    }

    val scannerName: String = "jqassistant"

    private val DEFAULT_KNOWN_KINDS = listOf(
        "Class", "Interface", "Enum", "Annotation", "Record", "Package", "Artifact"
    )
}
