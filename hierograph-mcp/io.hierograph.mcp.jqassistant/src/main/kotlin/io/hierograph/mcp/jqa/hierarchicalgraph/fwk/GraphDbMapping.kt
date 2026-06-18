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
package io.hierograph.mcp.jqa.hierarchicalgraph.fwk

/**
 * Declarative description of how a graph-DB is mapped onto a hierarchical graph.
 *
 * A [GraphDbMapping] is pure data: it pairs Cypher queries with the semantic kind
 * they produce, but knows nothing about the `hierarchicalgraph.spi` interfaces, the
 * bolt client, or how the queries are executed. The execution side is supplied by the
 * mapping-driven adapters ([MappingDrivenHierarchyProvider], [MappingDrivenDependencyProvider]),
 * which consume a mapping and implement the SPI.
 *
 * This split keeps the *what* (the domain mapping, e.g. "an `Artifact:Jar` is a module")
 * cleanly separate from the *how* (running queries, capturing metadata, building node sources).
 */
data class GraphDbMapping(
    val topLevelRules: List<TopLevelRule>,
    val parentChildRules: List<ParentChildRule>,
    val dependencyRules: List<DependencyRule>,
)

/**
 * Produces top-level nodes of the given [kind].
 *
 * [cypher] must `RETURN` three columns in order: the node id, its display name, and its
 * fully-qualified name.
 */
data class TopLevelRule(val kind: Any, val cypher: String)

/**
 * Produces parent→child edges whose child node is of the given [childKind].
 *
 * [cypher] must `RETURN` four columns in order: the parent id, the child id, the child's
 * display name, and the child's fully-qualified name.
 */
data class ParentChildRule(val childKind: Any, val cypher: String)

/**
 * Produces dependency edges.
 *
 * [cypher] must `RETURN`, in order: start id, target id, relationship id, relationship type,
 * weight, followed by zero or more boolean columns. Each boolean column sets one bit in the
 * dependency's attribute bitmap (column index 5 → bit 0, 6 → bit 1, …).
 */
data class DependencyRule(val cypher: String)
