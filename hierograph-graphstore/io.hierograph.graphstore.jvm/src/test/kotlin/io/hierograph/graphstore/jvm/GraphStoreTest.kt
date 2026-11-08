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
package io.hierograph.graphstore.jvm

import io.hierograph.graphstore.jvm.model.ArtifactInfo
import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.MemberFacts
import io.hierograph.graphstore.jvm.model.Origin
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.model.RefKind
import io.hierograph.graphstore.jvm.model.JavaKinds
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
    fun `applyClass builds the hierarchy down to the members`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass(
            "app",
            classFacts(
                "com.foo.A",
                members = listOf(
                    method("com.foo.A", "a()V"),
                    MemberFacts(key = "com.foo.A#<init>()V", kind = JavaKinds.CONSTRUCTOR, signature = "<init>()V"),
                    MemberFacts(key = "com.foo.A#x", kind = JavaKinds.FIELD, signature = "x"),
                ),
            ),
        )

        val rows = store.hierarchyRows().toList()
        // module (top-level), package chain com -> com.foo, the type, its members
        assertThat(rows).contains(HierarchyRow(JavaKinds.MODULE, "app", null))
        assertThat(rows).contains(HierarchyRow(JavaKinds.PACKAGE, "com", "app"))
        assertThat(rows).contains(HierarchyRow(JavaKinds.PACKAGE, "com.foo", "com"))
        assertThat(rows).contains(HierarchyRow(JavaKinds.CLASS, "com.foo.A", "com.foo"))
        assertThat(rows).contains(
            HierarchyRow(JavaKinds.METHOD, "com.foo.A#a()V", "com.foo.A"),
            HierarchyRow(JavaKinds.METHOD, "com.foo.A#<init>()V", "com.foo.A"), // constructors are java.method
            HierarchyRow(JavaKinds.FIELD, "com.foo.A#x", "com.foo.A"),
        )
        // members are children of the type, artifact-scoped, and not types
        assertThat(store.childKeysOf(store.typeKey("app", "com.foo.A"))).containsExactly(
            store.memberKey("app", "com.foo.A#a()V"),
            store.memberKey("app", "com.foo.A#<init>()V"),
            store.memberKey("app", "com.foo.A#x"),
        )
        assertThat(store.node(store.memberKey("app", "com.foo.A#a()V"))!!.name).isEqualTo("a()V")
        assertThat(store.typeByFqn("com.foo.A#a()V")).isNull()
    }

    @Test
    fun `members carry no dependencies of their own`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass(
            "app",
            classFacts(
                "com.foo.A",
                members = listOf(method("com.foo.A", "a()V")),
                refs = listOf(Ref("com.foo.A", "com.foo.B", RefKind.DEPENDS_ON, count = 1)),
            ),
        )

        val edges = store.typeDependencyEdges().toList()
        assertThat(edges).hasSize(1)
        assertThat(edges.single().fromKey).isEqualTo(store.typeKey("app", "com.foo.A"))
    }

    @Test
    fun `re-applying and removing a class replaces and drops its members`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass("app", classFacts("com.foo.A", members = listOf(method("com.foo.A", "a()V"))))
        store.applyClass("app", classFacts("com.foo.A", members = listOf(method("com.foo.A", "b()V"))))

        assertThat(store.contains(store.memberKey("app", "com.foo.A#a()V"))).isFalse
        assertThat(store.memberByKey("com.foo.A#a()V")).isNull()
        assertThat(store.memberByKey("com.foo.A#b()V")!!.key).isEqualTo(store.memberKey("app", "com.foo.A#b()V"))

        store.removeClass("app", "com.foo.A")
        assertThat(store.contains(store.memberKey("app", "com.foo.A#b()V"))).isFalse
        assertThat(store.memberByKey("com.foo.A#b()V")).isNull()
    }

    @Test
    fun `same-FQN types in two artifacts get distinct members and memberByKey follows typeByFqn`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.registerArtifact(artifact("lib"))
        store.applyClass("lib", classFacts("com.foo.A", members = listOf(method("com.foo.A", "a()V"))))
        store.applyClass("app", classFacts("com.foo.A", members = listOf(method("com.foo.A", "a()V"))))

        assertThat(store.contains(store.memberKey("app", "com.foo.A#a()V"))).isTrue
        assertThat(store.contains(store.memberKey("lib", "com.foo.A#a()V"))).isTrue
        // the member resolves under the same owner the type resolves to
        val owner = store.typeByFqn("com.foo.A")!!
        assertThat(store.memberByKey("com.foo.A#a()V")!!.parent).isEqualTo(owner.key)
        // a member of an unscanned owner does not resolve
        assertThat(store.memberByKey("java.util.List#size()I")).isNull()
    }

    @Test
    fun `unscanned ref targets become placeholders under the external module`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass(
            "app",
            classFacts("com.foo.A", refs = listOf(Ref("com.foo.A", "java.util.List", RefKind.DEPENDS_ON, count = 1))),
        )

        val placeholder = store.typeByFqn("java.util.List")
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
        assertThat(store.typeByFqn("com.foo.B")!!.placeholder).isTrue

        // now B is scanned for real
        store.applyClass("app", classFacts("com.foo.B"))

        val b = store.typeByFqn("com.foo.B")!!
        assertThat(b.placeholder).isFalse
        assertThat(b.kind).isEqualTo(JavaKinds.CLASS)
        assertThat(b.parent).isEqualTo("pkg:app:com.foo")           // re-homed out of <external>
        assertThat(store.incomingOwnersOf("com.foo.B")).contains("com.foo.A")
    }

    @Test
    fun `superseding a placeholder prunes its now-empty external package chain`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.registerArtifact(artifact("lib"))
        // app is ingested first: it references lib.B before lib is scanned, plus a truly external type
        store.applyClass(
            "app",
            classFacts(
                "com.app.A",
                refs = listOf(
                    Ref("com.app.A", "com.lib.sub.B", RefKind.DEPENDS_ON, count = 1),
                    Ref("com.app.A", "com.ext.C", RefKind.DEPENDS_ON, count = 1),
                ),
            ),
        )
        assertThat(store.contains("pkg:<external>:com.lib.sub")).isTrue

        store.applyClass("lib", classFacts("com.lib.sub.B"))

        // B's external package chain is gone ...
        assertThat(store.contains("pkg:<external>:com.lib.sub")).isFalse
        assertThat(store.contains("pkg:<external>:com.lib")).isFalse
        // ... but the shared ancestor still holding a placeholder is kept
        assertThat(store.contains("pkg:<external>:com")).isTrue
        assertThat(store.contains("pkg:<external>:com.ext")).isTrue
        assertThat(store.typeByFqn("com.ext.C")!!.placeholder).isTrue
        assertThat(store.childKeysOf("pkg:<external>:com")).containsExactly("pkg:<external>:com.ext")
        assertThat(store.typeByFqn("com.lib.sub.B")!!.parent).isEqualTo("pkg:lib:com.lib.sub")
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
    fun `re-applying a class replaces its owned refs`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass(
            "app",
            classFacts(
                "com.foo.A",
                refs = listOf(
                    Ref("com.foo.A", "com.foo.B", RefKind.DEPENDS_ON, count = 1),
                    Ref("com.foo.A", "com.foo.C", RefKind.DEPENDS_ON, count = 1),
                ),
            ),
        )
        assertThat(store.typeDependencies().map { it.to }.toList()).containsExactlyInAnyOrder("com.foo.B", "com.foo.C")

        // recompile drops the ref to C
        store.applyClass("app", classFacts("com.foo.A", refs = listOf(Ref("com.foo.A", "com.foo.B", RefKind.DEPENDS_ON, count = 1))))
        assertThat(store.typeDependencies().map { it.to }.toList()).containsExactly("com.foo.B")
        assertThat(store.incomingOwnersOf("com.foo.C")).doesNotContain("com.foo.A")
    }

    @Test
    fun `removeClass downgrades a still-referenced type and removes an unreferenced one`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass("app", classFacts("com.foo.A", refs = listOf(Ref("com.foo.A", "com.foo.B", RefKind.EXTENDS, count = 1))))
        store.applyClass("app", classFacts("com.foo.B"))
        store.applyClass("app", classFacts("com.foo.C"))

        // B is referenced by A -> downgrade to placeholder
        store.removeClass("app", "com.foo.B")
        val b = store.typeByFqn("com.foo.B")
        assertThat(b).isNotNull
        assertThat(b!!.placeholder).isTrue
        assertThat(b.kind).isEqualTo(JavaKinds.EXTERNAL_TYPE)

        // C has no incoming edges -> removed outright
        store.removeClass("app", "com.foo.C")
        assertThat(store.typeByFqn("com.foo.C")).isNull()
    }

    @Test
    fun `EXCLUDE_UBIQUITOUS policy excludes primitives arrays and ubiquitous types from placeholders`() {
        val store = GraphStore(PlaceholderPolicy.EXCLUDE_UBIQUITOUS)
        store.registerArtifact(artifact("app"))
        store.applyClass(
            "app",
            classFacts(
                "com.foo.A",
                refs = listOf(
                    Ref("com.foo.A", "int", RefKind.DEPENDS_ON, count = 1),
                    Ref("com.foo.A", "[I", RefKind.DEPENDS_ON, count = 1),
                    Ref("com.foo.A", "Task", RefKind.DEPENDS_ON, count = 1),            // default package (no dot)
                    Ref("com.foo.A", "java.lang.String", RefKind.DEPENDS_ON, count = 1),
                    Ref("com.foo.A", "kotlin.Unit", RefKind.DEPENDS_ON, count = 1),
                    Ref("com.foo.A", "com.other.Lib", RefKind.DEPENDS_ON, count = 1),   // normal external -> placeholder
                ),
            ),
        )
        // excluded targets: no placeholder node, and no external package chain
        assertThat(store.typeByFqn("int")).isNull()
        assertThat(store.typeByFqn("[I")).isNull()
        assertThat(store.typeByFqn("Task")).isNull()
        assertThat(store.typeByFqn("java.lang.String")).isNull()
        assertThat(store.typeByFqn("kotlin.Unit")).isNull()
        assertThat(store.hierarchyRows().map { it.fqn }.toList()).doesNotContain("java.lang", "kotlin")

        // a normal external type still becomes a placeholder
        assertThat(store.typeByFqn("com.other.Lib")!!.placeholder).isTrue

        // the refs are still facts: every target remains a type dependency even without a node
        assertThat(store.typeDependencies().map { it.to }.toList())
            .contains("int", "[I", "Task", "java.lang.String", "kotlin.Unit", "com.other.Lib")
    }

    @Test
    fun `resolvePlaceholders reconciles targets added out of order`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyClass("app", classFacts("com.foo.A", refs = listOf(Ref("com.foo.A", "com.foo.B", RefKind.EXTENDS, count = 1))))

        store.resolvePlaceholders() // idempotent: B already a placeholder from applyClass
        assertThat(store.typeByFqn("com.foo.B")!!.placeholder).isTrue
    }
}
