/*
 * Copyright 2024 Gerd Wuetherich
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
package io.hierograph.hierarchicalgraph.graphdb.mapping.spi

import io.hierograph.hierarchicalgraph.core.model.HGNode

interface INodeMetadataProvider {

    // Per-node metadata
    fun getName(node: HGNode): String
    fun getQualifiedName(node: HGNode): String
    fun getKind(node: HGNode): String
    fun getKindFromLabels(labels: List<String>): String
    fun getKnownKinds(): List<String>

    // Cypher query delegation
    fun getFindNodeCypherQuery(kind: String?, limit: Int): String
    fun getNodeCountCypherQuery(scopeId: Long?): String
    fun getDepthStatsCypherQuery(scopeId: Long?): String
    fun getDependencyKindDistributionCypherQuery(scopeId: Long?): String
    fun getScanMetadataCypherQuery(): String
    fun getScannerName(): String
}
