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
package io.hierograph.mcp.server.tools.navigation

import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.NodeMetadata

/**
 * The order in which `list_children` and `list_descendants` list the children of a node: by qualified
 * name ignoring case, then by qualified name, then by id as a number. Alphabetical for a reader
 * (`internal` beside `Invoice`), and total, so the order is the same on every call.
 */
internal val CHILD_ORDER: Comparator<HGNode> = compareBy<HGNode>(
    { NodeMetadata.getQualifiedName(it).lowercase() },
    { NodeMetadata.getQualifiedName(it) },
    { (it.identifier as? Number)?.toLong() ?: Long.MIN_VALUE },
)
