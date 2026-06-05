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
package io.hierograph.mcp.server.modulith

import io.hierograph.hierarchicalgraph.core.model.HGNodeTraverser
import io.hierograph.hierarchicalgraph.core.model.HGRootNode
import io.hierograph.mcp.javaspec.JavaEdgeAttributes
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.jqa.hierarchicalgraph.JQAssistantNodeMetadataProvider

/**
 * Overlays the authoritative Spring Modulith model onto the already-built hierarchical graph.
 *
 * For every type-level dependency that crosses a module boundary into a type that the target module
 * does not expose through its named interfaces, the [JavaEdgeAttributes.IS_MODULITH_VIOLATION] bit is
 * set on the underlying [io.hierograph.hierarchicalgraph.core.model.HGCoreDependency]. Because all
 * aggregation tools union the core bitmaps live, the flag propagates to every altitude automatically —
 * a module-pair cell in the DSM reads `is_modulith_violation = true` as soon as any underlying type
 * edge violates the boundary.
 *
 * The exposed set comes from Spring Modulith itself (named-interface propagation already applied), so
 * this avoids the false positives of re-deriving exposure in Cypher.
 */
object ModulithOverlay {

    data class Stats(val crossModuleEdges: Int, val violations: Int)

    fun apply(root: HGRootNode, model: ModulithModel): Stats {
        var crossModuleEdges = 0
        var violations = 0

        HGNodeTraverser.traverse(root) { node ->
            if (node.kind !in JavaKinds.TYPE_KINDS) return@traverse
            val sourceModule = model.moduleOf(JQAssistantNodeMetadataProvider.getQualifiedName(node))
                ?: return@traverse

            for (dep in node.outgoingCoreDependencies) {
                val target = dep.to
                if (target.kind !in JavaKinds.TYPE_KINDS) continue

                val targetFqn = JQAssistantNodeMetadataProvider.getQualifiedName(target)
                val targetModule = model.moduleOf(targetFqn) ?: continue
                if (targetModule.name == sourceModule.name) continue

                crossModuleEdges++
                if (targetFqn !in targetModule.exposedTypes) {
                    dep.attributesBitmap =
                        JavaEdgeAttributes.set(dep.attributesBitmap, JavaEdgeAttributes.IS_MODULITH_VIOLATION)
                    violations++
                }
            }
        }

        return Stats(crossModuleEdges, violations)
    }
}
