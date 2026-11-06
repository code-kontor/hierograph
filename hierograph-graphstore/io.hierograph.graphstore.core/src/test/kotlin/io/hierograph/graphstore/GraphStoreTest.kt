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
package io.hierograph.graphstore

import io.hierograph.graphstore.model.ArtifactInfo
import io.hierograph.graphstore.model.ClassFacts
import io.hierograph.graphstore.model.MemberFacts
import io.hierograph.graphstore.model.Origin
import io.hierograph.graphstore.model.Ref
import io.hierograph.graphstore.model.RefKind
import io.hierograph.mcp.javaspec.JavaKinds
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GraphStoreTest {

    private fun artifact(key: String) =
        ArtifactInfo(key = key, name = key, fqn = key, origin = Origin.MODULE_MAIN)

    private fun method(owner: String, sig: String) =
        MemberFacts(key = "$owner#$sig", kind = JavaKinds.METHOD, signature = sig)

    private fun classFacts(fqn: String, members: List<MemberFacts> = emptyList(), refs: List<Ref> = emptyList()) =
        ClassFacts(fqn = fqn, kind = JavaKinds.CLASS, modifiers = 0, members = members, refs = refs, classHash = "h:$fqn")

    @Test
    fun `applyClass builds the artifact-package-type-member hierarchy`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass("app", classFacts("com.foo.A", members = listOf(method("com.foo.A", "a()V"))))

        val rows = store.hierarchyRows().toList()
        // module (top-level), package chain com -> com.foo, the type, and its member
        assertThat(rows).contains(HierarchyRow(JavaKinds.MODULE, "app", null))
        assertThat(rows).contains(HierarchyRow(JavaKinds.PACKAGE, "com", "app"))
        assertThat(rows).contains(HierarchyRow(JavaKinds.PACKAGE, "com.foo", "com"))
        assertThat(rows).contains(HierarchyRow(JavaKinds.CLASS, "com.foo.A", "com.foo"))
        assertThat(rows).contains(HierarchyRow(JavaKinds.METHOD, "com.foo.A#a()V", "com.foo.A"))
    }

    @Test
    fun `unscanned ref targets become placeholders under the external module`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass(
            "app",
            classFacts("com.foo.A", refs = listOf(Ref("com.foo.A", "java.util.List", RefKind.DEPENDS_ON, count = 1))),
        )

        val placeholder = store.node("java.util.List")
        assertThat(placeholder).isNotNull
        assertThat(placeholder!!.placeholder).isTrue
        assertThat(placeholder.kind).isEqualTo(JavaKinds.EXTERNAL_TYPE)

        val rows = store.hierarchyRows().toList()
        assertThat(rows).contains(HierarchyRow(JavaKinds.MODULE, GraphStore.EXTERNAL_ARTIFACT_NAME, null))
        assertThat(rows).contains(HierarchyRow(JavaKinds.EXTERNAL_TYPE, "java.util.List", "java.util"))
    }

    @Test
    fun `scanning a referenced type upgrades its placeholder in place and keeps incoming edges`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass(
            "app",
            classFacts("com.foo.A", refs = listOf(Ref("com.foo.A", "com.foo.B", RefKind.EXTENDS, count = 1))),
        )
        assertThat(store.node("com.foo.B")!!.placeholder).isTrue

        // now B is scanned for real
        store.applyClass("app", classFacts("com.foo.B"))

        val b = store.node("com.foo.B")!!
        assertThat(b.placeholder).isFalse
        assertThat(b.kind).isEqualTo(JavaKinds.CLASS)
        assertThat(b.parent).isEqualTo("pkg:app:com.foo")           // re-homed out of <external>
        assertThat(store.incomingOwnersOf("com.foo.B")).contains("com.foo.A")
    }

    @Test
    fun `typeDependencies aggregates weight and sets structural flags`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass(
            "app",
            classFacts(
                "com.foo.A",
                refs = listOf(
                    Ref("com.foo.A", "com.foo.B", RefKind.EXTENDS, count = 1),
                    Ref("com.foo.A", "java.util.List", RefKind.DEPENDS_ON, count = 3),
                ),
            ),
        )

        val deps = store.typeDependencies().associateBy { it.to }
        assertThat(deps.keys).containsExactlyInAnyOrder("com.foo.B", "java.util.List")

        val toB = deps.getValue("com.foo.B")
        assertThat(toB.extends).isTrue
        assertThat(toB.other).isFalse
        assertThat(toB.weight).isEqualTo(1)

        val toList = deps.getValue("java.util.List")
        assertThat(toList.extends).isFalse
        assertThat(toList.other).isTrue
        assertThat(toList.weight).isEqualTo(3)
    }

    @Test
    fun `a structural edge coexisting with a non-structural ref sets both extends and other`() {
        // Documents the spec's open 'other'-flag divergence from the jQAssistant DependencyRule.
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass(
            "app",
            classFacts(
                "com.foo.A",
                refs = listOf(
                    Ref("com.foo.A", "com.foo.B", RefKind.EXTENDS, count = 1),
                    Ref("com.foo.A", "com.foo.B", RefKind.DEPENDS_ON, count = 5),
                ),
            ),
        )

        val toB = store.typeDependencies().single { it.to == "com.foo.B" }
        assertThat(toB.extends).isTrue
        assertThat(toB.other).isTrue
        assertThat(toB.weight).isEqualTo(6)
    }

    @Test
    fun `re-applying a class replaces its owned subgraph`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass("app", classFacts("com.foo.A", members = listOf(method("com.foo.A", "a()V"), method("com.foo.A", "b()V"))))
        assertThat(store.childKeysOf("com.foo.A")).containsExactlyInAnyOrder("com.foo.A#a()V", "com.foo.A#b()V")

        // recompile drops method b
        store.applyClass("app", classFacts("com.foo.A", members = listOf(method("com.foo.A", "a()V"))))
        assertThat(store.childKeysOf("com.foo.A")).containsExactly("com.foo.A#a()V")
        assertThat(store.contains("com.foo.A#b()V")).isFalse
    }

    @Test
    fun `removeClass downgrades a still-referenced type and removes an unreferenced one`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass("app", classFacts("com.foo.A", refs = listOf(Ref("com.foo.A", "com.foo.B", RefKind.EXTENDS, count = 1))))
        store.applyClass("app", classFacts("com.foo.B"))
        store.applyClass("app", classFacts("com.foo.C"))

        // B is referenced by A -> downgrade to placeholder
        store.removeClass("com.foo.B")
        val b = store.node("com.foo.B")
        assertThat(b).isNotNull
        assertThat(b!!.placeholder).isTrue
        assertThat(b.kind).isEqualTo(JavaKinds.EXTERNAL_TYPE)

        // C has no incoming edges -> removed outright
        store.removeClass("com.foo.C")
        assertThat(store.contains("com.foo.C")).isFalse
    }

    @Test
    fun `resolvePlaceholders reconciles targets added out of order`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass("app", classFacts("com.foo.A", refs = listOf(Ref("com.foo.A", "com.foo.B", RefKind.EXTENDS, count = 1))))

        store.resolvePlaceholders() // idempotent: B already a placeholder from applyClass
        assertThat(store.node("com.foo.B")!!.placeholder).isTrue
    }
}
