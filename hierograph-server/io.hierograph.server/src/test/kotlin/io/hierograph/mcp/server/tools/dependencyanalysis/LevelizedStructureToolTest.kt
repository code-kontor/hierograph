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
package io.hierograph.mcp.server.tools.dependencyanalysis

import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.model.DefaultNodeSource
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.mcp.server.tools.NodeRefFactory
import io.hierograph.server.graphservice.HierarchicalGraphService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** `levelized_structure`: a container's children in rows by level, its tangles and their feedback dependencies (#61). */
@Suppress("UNCHECKED_CAST")
class LevelizedStructureToolTest {

    /** A module of packages, each holding one class, with weighted dependencies between the classes. */
    private class Fixture {
        private var nextId = 1L
        private val nodeSource = { DefaultNodeSource(identifier = nextId++) }
        private val depSource = { DefaultDependencySource(identifier = nextId++) }
        private val graph = HGGraphFactory.createHGGraph()
        private val root = HGGraphFactory.createNode(graph, nodeSource)
        private val hierarchy = HierarchyFactory.createHierarchy(graph, root)
        val module: HGNode = HGGraphFactory.createNode(graph, nodeSource).also {
            it.kind = JavaNodeKind.MODULE
            HierarchyFactory.addChild(hierarchy, root, it)
        }
        private val classOf = HashMap<Long, HGNode>()

        fun pkg(): Long {
            val pkg = HGGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.PACKAGE }
            HierarchyFactory.addChild(hierarchy, module, pkg)
            val cls = HGGraphFactory.createNode(graph, nodeSource).also { it.kind = JavaNodeKind.CLASS }
            HierarchyFactory.addChild(hierarchy, pkg, cls)
            return (pkg.identifier as Long).also { classOf[it] = cls }
        }

        fun dep(from: Long, to: Long, weight: Int) {
            HGGraphFactory.createCoreDependency(classOf.getValue(from), classOf.getValue(to), "USES", depSource)
                .also { it.weight = weight }
        }

        fun tool(): LevelizedStructureTool {
            val graphService = HierarchicalGraphService().also { it.seed(HGModel(graph, hierarchy)) }
            return LevelizedStructureTool(graphService, NodeRefFactory(graphService))
        }
    }

    @Test
    fun `a module's packages in rows bottom first, a tangle on one row, its feedback dependency heaviest first`() {
        val f = Fixture()
        val app = f.pkg(); val core = f.pkg(); val util = f.pkg(); val base = f.pkg()
        f.dep(app, core, 3)
        f.dep(core, util, 5); f.dep(util, core, 2)
        f.dep(util, base, 1)

        val response = f.tool().levelizedStructure(f.module.identifier as Long, null)

        val levels = response["levels"] as List<Map<String, Any?>>
        assertThat(levels.map { it["level"] }).containsExactly(0, 1, 2)
        assertThat(levels.map { (it["node_ids"] as List<Long>).toSet() })
            .containsExactly(setOf(base), setOf(core, util), setOf(app))

        val tangle = (response["tangles"] as List<Map<String, Any?>>).single()
        assertThat(tangle["level"]).isEqualTo(1)
        assertThat(tangle["member_ids"] as List<Long>).containsExactly(core, util)
        assertThat(tangle["rows"] as List<List<Long>>).containsExactly(listOf(util), listOf(core))
        assertThat(tangle["feedback_weight"]).isEqualTo(2)

        val feedback = (response["feedback_dependencies"] as List<Map<String, Any?>>).single()
        assertThat(feedback).containsEntry("from", util).containsEntry("to", core)
            .containsEntry("weight", 2).containsEntry("type_dependency_count", 1)

        val summary = response["summary"] as Map<String, Any?>
        assertThat(summary).containsEntry("children", 4).containsEntry("levels", 3).containsEntry("acyclic", false)
            .containsEntry("tangles", 1).containsEntry("feedback_dependencies", 1).containsEntry("truncated", false)
        assertThat((response["nodes"] as Map<String, Any?>).keys).containsExactlyInAnyOrder(
            app.toString(), core.toString(), util.toString(), base.toString()
        )
    }

    @Test
    fun `the feedback dependencies are cut at the limit, the summary counts all of them`() {
        val f = Fixture()
        val a = f.pkg(); val b = f.pkg(); val c = f.pkg(); val d = f.pkg()
        f.dep(a, b, 9); f.dep(b, a, 4)
        f.dep(c, d, 9); f.dep(d, c, 1)

        val response = f.tool().levelizedStructure(f.module.identifier as Long, 1)

        val feedback = response["feedback_dependencies"] as List<Map<String, Any?>>
        assertThat(feedback.single()).containsEntry("from", b).containsEntry("weight", 4)
        assertThat(response["summary"] as Map<String, Any?>)
            .containsEntry("feedback_dependencies", 2).containsEntry("feedback_weight", 5)
            .containsEntry("returned", 1).containsEntry("truncated", true)
    }

    @Test
    fun `an acyclic container has no tangles, an unknown id is NODE_NOT_FOUND`() {
        val f = Fixture()
        val a = f.pkg(); val b = f.pkg()
        f.dep(a, b, 1)
        val tool = f.tool()

        val response = tool.levelizedStructure(f.module.identifier as Long, null)
        assertThat(response["summary"] as Map<String, Any?>).containsEntry("acyclic", true).containsEntry("tangles", 0)
        assertThat(response["tangles"] as List<*>).isEmpty()

        val error = tool.levelizedStructure(424242L, null)["error"] as Map<String, Any?>
        assertThat(error["code"]).isEqualTo("NODE_NOT_FOUND")
    }
}
