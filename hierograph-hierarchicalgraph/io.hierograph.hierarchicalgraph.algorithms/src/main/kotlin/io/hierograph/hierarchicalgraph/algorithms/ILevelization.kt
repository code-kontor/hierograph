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

import io.hierograph.hierarchicalgraph.model.AggregatedDependency
import io.hierograph.hierarchicalgraph.model.HGNode

/**
 * A node set laid out in rows by level, as a levelized structure map draws the children of a container: a node
 * sits above everything it depends on, and the nodes of a cycle (a tangle) share a row. Ordinary dependencies
 * then all point down; only the [feedbackDependencies] inside the tangles point against the levels.
 */
interface ILevelization {

    /**
     * The rows, bottom first: `levels[0]` holds the nodes that depend on no other node of the set, and a node is
     * one row above the highest row it depends on. Each row lists its nodes in matrix order (dependents first,
     * a tangle's members together); the order within a row carries no meaning beyond that.
     */
    val levels: List<List<HGNode>>

    /** The tangles: the cycles of two or more nodes, sorted by their first member in matrix order. */
    val tangles: List<Tangle>

    /**
     * The dependencies against a tangle's order: from a member to one placed before it. They are the cheapest set
     * whose removal untangles the tangles, and so the refactoring candidates. Heaviest first.
     */
    val feedbackDependencies: List<AggregatedDependency>

    /** The row [node] sits in; `-1` for a node not in the set. */
    fun levelOf(node: HGNode): Int
}

/**
 * A cycle of two or more nodes, all in row [level].
 *
 * @property members the members, dependents first: in the order that keeps the heaviest dependencies pointing
 *   down (the DSM's order); a dependency from a member to an earlier one is a feedback dependency.
 * @property rows the members in rows inside the tangle, bottom first: a member sits above the members after it
 *   that it depends on, ignoring the feedback dependencies.
 */
data class Tangle(val level: Int, val members: List<HGNode>, val rows: List<List<HGNode>>)
