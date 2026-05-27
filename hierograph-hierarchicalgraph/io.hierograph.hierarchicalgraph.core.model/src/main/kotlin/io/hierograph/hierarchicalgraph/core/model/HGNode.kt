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
package io.hierograph.hierarchicalgraph.core.model

interface HGNode {
    var kind: Any?
    val parent: HGNode?
    val children: List<HGNode>
    val nodeSource: INodeSource

    val identifier: Any
    val rootNode: HGRootNode
    val predecessors: List<HGNode>

    val outgoingCoreDependencies: List<HGCoreDependency>
    val incomingCoreDependencies: List<HGCoreDependency>
    val accumulatedOutgoingCoreDependencies: List<HGCoreDependency>
    val accumulatedIncomingCoreDependencies: List<HGCoreDependency>

    fun isPredecessorOf(node: HGNode?): Boolean
    fun isSuccessorOf(node: HGNode?): Boolean

    fun getOutgoingDependenciesTo(target: HGNode): HGAggregatedDependency?
    fun getOutgoingDependenciesTo(targets: List<HGNode>): List<HGAggregatedDependency>
    fun getIncomingDependenciesFrom(source: HGNode): HGAggregatedDependency?
    fun getIncomingDependenciesFrom(sources: List<HGNode>): List<HGAggregatedDependency>

    fun <T : Any> getNodeSource(clazz: Class<T>): T?
}
