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

import io.hierograph.hierarchicalgraph.core.model.DefaultDependencySource
import io.hierograph.hierarchicalgraph.core.model.HGCoreDependency
import io.hierograph.hierarchicalgraph.core.model.HGNode
import io.hierograph.hierarchicalgraph.core.model.HGRootNode
import io.hierograph.hierarchicalgraph.core.model.HierarchicalGraphFactory
import io.hierograph.mcp.javaspec.JavaEdgeAttributes
import io.hierograph.mcp.javaspec.JavaNodeKind
import io.hierograph.mcp.jqa.hierarchicalgraph.ExtendedGraphDbNodeSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

class ModulithOverlayTest {

    private var nextId = 1L

    /** Two modules: 'a' exposes only app.a.Api, 'b' exposes only app.b.Pub (app.b.Internal is internal). */
    private val model: ModulithModel = run {
        val json = """
            { "modules": [
              { "name": "a", "basePackage": "app.a", "exposedTypes": ["app.a.Api"] },
              { "name": "b", "basePackage": "app.b", "exposedTypes": ["app.b.Pub"] }
            ] }
        """.trimIndent()
        val file = Files.createTempFile("modulith-model", ".json").toFile().apply {
            writeText(json); deleteOnExit()
        }
        ModulithModel.read(file)
    }

    @Test
    fun `flags only cross-module edges into non-exposed types`() {
        val root = HierarchicalGraphFactory.createRootNode { ExtendedGraphDbNodeSource(nextId++, "root", "") }

        val caller = typeNode(root, "app.a.Caller")     // module a
        val api = typeNode(root, "app.a.Api")           // module a, exposed
        val pub = typeNode(root, "app.b.Pub")           // module b, exposed
        val internal = typeNode(root, "app.b.Internal") // module b, NOT exposed

        val toApi = dependency(caller, api)            // same module        -> never a violation
        val toPub = dependency(caller, pub)            // cross-module, exposed -> allowed
        val toInternal = dependency(caller, internal)  // cross-module, internal -> VIOLATION

        val stats = ModulithOverlay.apply(root, model)

        assertThat(stats.crossModuleEdges).isEqualTo(2)
        assertThat(stats.violations).isEqualTo(1)
        assertThat(isViolation(toInternal)).isTrue()
        assertThat(isViolation(toPub)).isFalse()
        assertThat(isViolation(toApi)).isFalse()
    }

    private fun typeNode(root: HGRootNode, fqn: String): HGNode =
        HierarchicalGraphFactory.createNode(root, root) {
            ExtendedGraphDbNodeSource(nextId++, fqn.substringAfterLast('.'), fqn)
        }.apply { kind = JavaNodeKind.CLASS }

    private fun dependency(from: HGNode, to: HGNode): HGCoreDependency =
        HierarchicalGraphFactory.createCoreDependency(from, to, "USES") { DefaultDependencySource(identifier = nextId++) }

    private fun isViolation(dep: HGCoreDependency): Boolean =
        JavaEdgeAttributes.isSet(dep.attributesBitmap, JavaEdgeAttributes.IS_MODULITH_VIOLATION)
}
