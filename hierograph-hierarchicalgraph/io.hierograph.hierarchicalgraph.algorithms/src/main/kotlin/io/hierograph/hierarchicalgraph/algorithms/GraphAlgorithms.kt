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
package io.hierograph.hierarchicalgraph.algorithms

import io.hierograph.hierarchicalgraph.algorithms.internal.DependencyStructureMatrixImpl
import io.hierograph.hierarchicalgraph.algorithms.internal.FastFasSorter
import io.hierograph.hierarchicalgraph.algorithms.internal.LevelizationImpl
import io.hierograph.hierarchicalgraph.algorithms.internal.Tarjan
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.Hierarchy

/**
 * The structural analyses of a node set: strongly connected components and cycles (specification, §5), node
 * ordering (§6), the dependency structure matrix (§7) and the levelization (§7.3). For the raw aggregated
 * dependencies among a node set, see [DependencyAggregation].
 */
object GraphAlgorithms {

    fun detectStronglyConnectedComponents(nodes: Collection<HGNode>, hierarchy: Hierarchy): List<List<HGNode>> =
        Tarjan().detectStronglyConnectedComponents(nodes, hierarchy)

    fun detectCycles(nodes: Collection<HGNode>, hierarchy: Hierarchy): List<List<HGNode>> =
        Tarjan().detectCycles(nodes, hierarchy)

    fun createFasNodeSorter(): INodeSorter = FastFasSorter()

    fun createDependencyStructureMatrix(nodes: Collection<HGNode>, hierarchy: Hierarchy): IDependencyStructureMatrix =
        DependencyStructureMatrixImpl(nodes, hierarchy)

    /** The levelization of [nodes]: rows by level, tangles and feedback dependencies (see [ILevelization]). */
    fun levelize(nodes: Collection<HGNode>, hierarchy: Hierarchy): ILevelization = LevelizationImpl(nodes, hierarchy)
}
