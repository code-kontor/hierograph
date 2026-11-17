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
package io.hierograph.server.views

import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import io.hierograph.hierarchicalgraph.model.HGGraphFactory
import io.hierograph.hierarchicalgraph.model.HGModel
import io.hierograph.hierarchicalgraph.model.HGNode
import io.hierograph.hierarchicalgraph.model.HierarchyFactory
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.hierarchicalgraph.model.SyntheticNodeSource

/**
 * A small reactor for view tests:
 * ```
 * acme:app:1.0          com.acme.app      { Main, MainHelper }
 * acme:app:1.0:test     com.acme.app      { MainTest }
 * acme:core:1.0         com.acme.core     { Engine, EngineImpl }, com.acme.core.spi { Plugin }
 * acme:core-util:1.0    com.acme.util     { Strings }
 * ```
 * Each module holds the nested package path `com` → `com.acme` → … as the scanner produces it. Node
 * identifiers are handles of their qualified names (plus a counter, since package names repeat), as in the
 * served graph. [dependency] adds a core dependency between two types.
 */
class TestReactor {
    private var nextId = 1L
    val graph = HGGraphFactory.createHGGraph()
    val root = node("", null, parent = null)
    val hierarchy = HierarchyFactory.createHierarchy(graph, root)
    val byName = HashMap<String, MutableList<HGNode>>()

    fun node(qualifiedName: String, kind: JavaNodeKind?, parent: HGNode?): HGNode {
        val simple = qualifiedName.substringAfterLast('.')
        val node = HGGraphFactory.createNode(graph) { SyntheticNodeSource(NodeHandle.of("$qualifiedName#${nextId++}"), simple, qualifiedName) }
        node.kind = kind
        if (parent != null) {
            HierarchyFactory.addChild(hierarchy, parent, node)
            byName.getOrPut(qualifiedName) { mutableListOf() } += node
        }
        return node
    }

    fun module(gav: String) = node(gav, JavaNodeKind.MODULE, root)

    /** Creates the package path down to [fqn] below [module] and returns the innermost package. */
    fun pkg(module: HGNode, fqn: String): HGNode {
        var parent = module
        val parts = fqn.split('.')
        for (i in parts.indices) {
            val name = parts.take(i + 1).joinToString(".")
            parent = hierarchy.childrenOf(parent).firstOrNull { it.nodeSource.qualifiedName == name }
                ?: node(name, JavaNodeKind.PACKAGE, parent)
        }
        return parent
    }

    fun type(pkg: HGNode, simple: String) = node("${pkg.nodeSource.qualifiedName}.$simple", JavaNodeKind.CLASS, pkg)

    val app = module("acme:app:1.0")
    val appTest = module("acme:app:1.0:test")
    val core = module("acme:core:1.0")
    val coreUtil = module("acme:core-util:1.0")

    val appPkg = pkg(app, "com.acme.app")
    val main = type(appPkg, "Main")
    val mainHelper = type(appPkg, "MainHelper")
    val appTestPkg = pkg(appTest, "com.acme.app")
    val mainTest = type(appTestPkg, "MainTest")
    val corePkg = pkg(core, "com.acme.core")
    val engine = type(corePkg, "Engine")
    val engineImpl = type(corePkg, "EngineImpl")
    val spiPkg = pkg(core, "com.acme.core.spi")
    val plugin = type(spiPkg, "Plugin")
    val utilPkg = pkg(coreUtil, "com.acme.util")
    val strings = type(utilPkg, "Strings")

    val model: HGModel get() = HGModel(graph, hierarchy)

    fun dependency(from: HGNode, to: HGNode, weight: Int = 1) {
        HGGraphFactory.createCoreDependency(from, to, "DEPENDS_ON") { io.hierograph.hierarchicalgraph.model.DefaultDependencySource(identifier = "dep:${nextId++}") }.weight = weight
    }
}
