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
package io.hierograph.graphservice

import io.hierograph.graphstore.GraphStore
import io.hierograph.graphstore.hgmodel.GraphStoreNodeSource
import io.hierograph.graphstore.model.MemberModifiers
import io.hierograph.graphstore.model.TypeModifiers
import io.hierograph.graphstore.parquet.ParquetReader
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.Hierarchy
import io.hierograph.mcp.javaspec.JavaKinds
import io.hierograph.mcp.javaspec.JavaNodeKind
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * `Node.properties` for the Parquet-backed graph, computed on demand. Structural counts come from the
 * in-memory hierarchy; per-type snapshot facts (`sourceFile`, `classHash`, modifiers, member counts) are
 * read from `types.parquet` / `members.parquet`, scoped to just the requested types — one filtered read
 * per table per batch, nothing kept in memory. In `jqa` mode it answers nothing (those nodes carry Neo4j
 * properties of their own).
 */
@Component
class ParquetNodePropertiesProvider(private val graphService: HierarchicalGraphService) : NodePropertiesProvider {

    override fun propertiesOf(nodes: List<HGNode>): Map<HGNode, Map<String, String?>> {
        if (!graphService.isParquetBacked || !graphService.isGraphAvailable) return emptyMap()
        val hierarchy = graphService.model.hierarchy
        val dir = Path.of(graphService.parquetDirectory)

        val scannedTypes = nodes.filter { it.kind in JavaKinds.TYPE_KINDS }
        val typeFqns = scannedTypes.mapNotNullTo(HashSet()) { source(it)?.qualifiedName }
        val reader = ParquetReader()
        val typeRows = reader.readTypeRows(dir, typeFqns).associateBy { it.artifact to it.fqn }
        val membersByOwner = reader.readMembersByOwner(dir, typeFqns)

        return nodes.associateWith { node ->
            val source = source(node) ?: return@associateWith emptyMap()
            buildMap {
                put("fqn", source.qualifiedName)
                put("name", source.name)
                put("kind", node.kind?.toString())
                when {
                    node.kind == JavaKinds.MODULE -> {
                        if (source.key == GraphStore.EXTERNAL_ARTIFACT_KEY) put("external", "true")
                        else put("artifact", source.key)
                        put("packageCount", countDescendants(hierarchy, node) { it.kind == JavaKinds.PACKAGE }.toString())
                        put("typeCount", countDescendants(hierarchy, node) { JavaKinds.isTypeKind(it.kind) }.toString())
                    }
                    node.kind == JavaKinds.PACKAGE -> {
                        val children = hierarchy.childrenOf(node)
                        putArtifact(hierarchy, node)
                        put("subPackageCount", children.count { it.kind == JavaKinds.PACKAGE }.toString())
                        put("directTypeCount", children.count { JavaKinds.isTypeKind(it.kind) }.toString())
                        put("descendantTypeCount", countDescendants(hierarchy, node) { JavaKinds.isTypeKind(it.kind) }.toString())
                    }
                    node.kind == JavaKinds.EXTERNAL_TYPE -> put("external", "true")
                    node.kind in JavaKinds.TYPE_KINDS -> {
                        val artifact = putArtifact(hierarchy, node)
                        typeRows[artifact to source.qualifiedName]?.let { row ->
                            row.sourceFile?.let { put("sourceFile", it) }
                            row.classHash?.takeIf { it.isNotEmpty() }?.let { put("classHash", it) }
                            // Omitted (rather than guessed) for snapshots written before the modifiers column.
                            row.modifiers?.let { bits ->
                                put("visibility", MemberModifiers.visibility(bits))
                                put("modifiers", TypeModifiers.modifiers(bits).joinToString(" "))
                                put("abstract", TypeModifiers.isAbstract(bits).toString())
                                put("final", TypeModifiers.isFinal(bits).toString())
                                put("static", TypeModifiers.isStatic(bits).toString())
                            }
                        }
                        val members = membersByOwner[source.qualifiedName].orEmpty()
                        put("methodCount", members.count { it.kind == JavaNodeKind.METHOD }.toString())
                        put("constructorCount", members.count { it.kind == JavaNodeKind.CONSTRUCTOR }.toString())
                        put("fieldCount", members.count { it.kind == JavaNodeKind.FIELD }.toString())
                    }
                }
            }
        }
    }

    private fun source(node: HGNode): GraphStoreNodeSource? = node.nodeSource as? GraphStoreNodeSource

    /** Puts the key of [node]'s owning module as `artifact` (unless it is the external module) and returns it. */
    private fun MutableMap<String, String?>.putArtifact(hierarchy: Hierarchy, node: HGNode): String? {
        var n: HGNode? = node
        while (n != null && n.kind != JavaKinds.MODULE) n = hierarchy.parentOf(n)
        val key = n?.let { source(it)?.key }?.takeIf { it != GraphStore.EXTERNAL_ARTIFACT_KEY } ?: return null
        put("artifact", key)
        return key
    }

    private fun countDescendants(hierarchy: Hierarchy, node: HGNode, predicate: (HGNode) -> Boolean): Int {
        var count = 0
        fun walk(n: HGNode) {
            for (child in hierarchy.childrenOf(n)) {
                if (predicate(child)) count++
                walk(child)
            }
        }
        walk(node)
        return count
    }
}
