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
import io.hierograph.graphstore.jvm.archive.model.ClassFacts
import io.hierograph.graphstore.jvm.archive.model.MemberFacts
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.Test

class GraphStoreTest {

    private fun artifact(key: String) =
        ArtifactInfo(key = key, name = key, fqn = key)

    private fun method(owner: String, sig: String) =
        MemberFacts(key = "$owner#$sig", kind = JavaNodeKind.METHOD, signature = sig)

    private fun GraphStore.applyType(artifact: String, fqn: String, members: List<MemberFacts> = emptyList(), refs: List<Ref> = emptyList()) =
        applyClass(artifact, ClassFacts(fqn = fqn, kind = JavaNodeKind.CLASS, modifiers = 0, members = members, classHash = "h:$fqn"), refs)

    @Test
    fun `applyClass builds the hierarchy down to the members`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyType(
            "app",
            "com.foo.A",
            members = listOf(
                method("com.foo.A", "a()V"),
                MemberFacts(key = "com.foo.A#<init>()V", kind = JavaNodeKind.CONSTRUCTOR, signature = "<init>()V"),
                MemberFacts(key = "com.foo.A#x", kind = JavaNodeKind.FIELD, signature = "x"),
            ),
        )

        val rows = store.nodes().map { tuple(it.kind, it.fqn, it.parent?.let(store::node)?.fqn) }
        // module (top-level), package chain com -> com.foo, the type, its members
        assertThat(rows).contains(tuple(JavaNodeKind.MODULE, "app", null))
        assertThat(rows).contains(tuple(JavaNodeKind.PACKAGE, "com", "app"))
        assertThat(rows).contains(tuple(JavaNodeKind.PACKAGE, "com.foo", "com"))
        assertThat(rows).contains(tuple(JavaNodeKind.CLASS, "com.foo.A", "com.foo"))
        assertThat(rows).contains(
            tuple(JavaNodeKind.METHOD, "com.foo.A#a()V", "com.foo.A"),
            tuple(JavaNodeKind.METHOD, "com.foo.A#<init>()V", "com.foo.A"), // constructors are java.method
            tuple(JavaNodeKind.FIELD, "com.foo.A#x", "com.foo.A"),
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
        store.applyType(
            "app",
            "com.foo.A",
            members = listOf(method("com.foo.A", "a()V")),
            refs = listOf(Ref("com.foo.B", DependencyKind.CALLS, count = 1)),
        )

        val edges = store.typeDependencies().toList()
        assertThat(edges).hasSize(1)
        assertThat(edges.single().fromKey).isEqualTo(store.typeKey("app", "com.foo.A"))
    }

    @Test
    fun `re-applying a class replaces its members`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyType("app", "com.foo.A", members = listOf(method("com.foo.A", "a()V")))
        store.applyType("app", "com.foo.A", members = listOf(method("com.foo.A", "b()V")))

        assertThat(store.node(store.memberKey("app", "com.foo.A#a()V"))).isNull()
        assertThat(store.memberByKey("com.foo.A#a()V")).isNull()
        assertThat(store.memberByKey("com.foo.A#b()V")!!.key).isEqualTo(store.memberKey("app", "com.foo.A#b()V"))
    }

    @Test
    fun `same-FQN types in two artifacts get distinct members and memberByKey follows typeByFqn`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.registerArtifact(artifact("lib"))
        store.applyType("lib", "com.foo.A", members = listOf(method("com.foo.A", "a()V")))
        store.applyType("app", "com.foo.A", members = listOf(method("com.foo.A", "a()V")))

        assertThat(store.node(store.memberKey("app", "com.foo.A#a()V"))).isNotNull
        assertThat(store.node(store.memberKey("lib", "com.foo.A#a()V"))).isNotNull
        // the member resolves under the same owner the type resolves to
        val owner = store.typeByFqn("com.foo.A")!!
        assertThat(store.memberByKey("com.foo.A#a()V")!!.parent).isEqualTo(owner.key)
        // a member of an unscanned owner does not resolve
        assertThat(store.memberByKey("java.util.List#size()I")).isNull()
    }

    @Test
    fun `memberOf finds the member of exactly the given owner, with GAV artifact keys`() {
        val store = GraphStore()
        val app = "com.acme:app:1.0"
        val lib = "com.acme:lib:1.0:test"
        store.registerArtifact(artifact(app))
        store.registerArtifact(artifact(lib))
        store.applyType(lib, "com.foo.A", members = listOf(method("com.foo.A", "a()V")))
        store.applyType(app, "com.foo.A", members = listOf(method("com.foo.A", "a()V"), method("com.foo.A", "b()V")))

        val inApp = store.typeIn(app, "com.foo.A")!!
        val inLib = store.typeIn(lib, "com.foo.A")!!
        assertThat(store.memberOf(inApp, "com.foo.A#a()V")!!.key).isEqualTo(store.memberKey(app, "com.foo.A#a()V"))
        assertThat(store.memberOf(inLib, "com.foo.A#a()V")!!.key).isEqualTo(store.memberKey(lib, "com.foo.A#a()V"))
        assertThat(store.memberOf(inLib, "com.foo.A#b()V")).isNull() // only the app's A declares b()
        assertThat(store.memberOf(inApp, "com.foo.A#c()V")).isNull()
        // a node that is not a type has no members
        assertThat(store.memberOf(store.node(app)!!, "com.foo.A#a()V")).isNull()
    }

    @Test
    fun `unscanned ref targets become placeholders under the external module`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyType(
            "app",
            "com.foo.A", refs = listOf(Ref("java.util.List", DependencyKind.CALLS, count = 1)),
        )

        val placeholder = store.typeByFqn("java.util.List")
        assertThat(placeholder).isNotNull
        assertThat(placeholder!!.placeholder).isTrue
        assertThat(placeholder.kind).isEqualTo(JavaNodeKind.EXTERNAL_TYPE)

        val rows = store.nodes().map { tuple(it.kind, it.fqn, it.parent?.let(store::node)?.fqn) }
        assertThat(rows).contains(tuple(JavaNodeKind.MODULE, GraphStore.EXTERNAL_ARTIFACT_NAME, null))
        assertThat(rows).contains(tuple(JavaNodeKind.EXTERNAL_TYPE, "java.util.List", "java.util"))
    }

    @Test
    fun `scanning a referenced type upgrades its placeholder in place and keeps incoming edges`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyType(
            "app",
            "com.foo.A", refs = listOf(Ref("com.foo.B", DependencyKind.EXTENDS, count = 1)),
        )
        assertThat(store.typeByFqn("com.foo.B")!!.placeholder).isTrue

        // now B is scanned for real
        store.applyType("app", "com.foo.B")

        val b = store.typeByFqn("com.foo.B")!!
        assertThat(b.placeholder).isFalse
        assertThat(b.kind).isEqualTo(JavaNodeKind.CLASS)
        assertThat(b.parent).isEqualTo("pkg:app:com.foo")           // re-homed out of <external>
        assertThat(store.typeDependencies().filter { it.toFqn == "com.foo.B" }.map { it.fromFqn }.toList()).contains("com.foo.A")
    }

    @Test
    fun `superseding a placeholder prunes its now-empty external package chain`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.registerArtifact(artifact("lib"))
        // app is ingested first: it references lib.B before lib is scanned, plus a truly external type
        store.applyType(
            "app",
            "com.app.A",
            refs = listOf(
                Ref("com.lib.sub.B", DependencyKind.CALLS, count = 1),
                Ref("com.ext.C", DependencyKind.CALLS, count = 1),
            ),
        )
        assertThat(store.node("pkg:<external>:com.lib.sub")).isNotNull

        store.applyType("lib", "com.lib.sub.B")

        // B's external package chain is gone ...
        assertThat(store.node("pkg:<external>:com.lib.sub")).isNull()
        assertThat(store.node("pkg:<external>:com.lib")).isNull()
        // ... but the shared ancestor still holding a placeholder is kept
        assertThat(store.node("pkg:<external>:com")).isNotNull
        assertThat(store.node("pkg:<external>:com.ext")).isNotNull
        assertThat(store.typeByFqn("com.ext.C")!!.placeholder).isTrue
        assertThat(store.childKeysOf("pkg:<external>:com")).containsExactly("pkg:<external>:com.ext")
        assertThat(store.typeByFqn("com.lib.sub.B")!!.parent).isEqualTo("pkg:lib:com.lib.sub")
    }

    @Test
    fun `typeDependencies counts the kinds and derives weight and structural flags from them`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyType(
            "app",
            "com.foo.A",
            refs = listOf(
                Ref("com.foo.B", DependencyKind.EXTENDS, count = 1),
                Ref("java.util.List", DependencyKind.CALLS, count = 3),
                Ref("java.util.List", DependencyKind.RETURNS, count = 2),
            ),
        )

        val deps = store.typeDependencies().associateBy { it.toFqn }
        assertThat(deps.keys).containsExactlyInAnyOrder("com.foo.B", "java.util.List")

        val toB = deps.getValue("com.foo.B")
        assertThat(toB.extends).isTrue
        assertThat(toB.other).isFalse
        assertThat(toB.weight).isEqualTo(1)

        val toList = deps.getValue("java.util.List")
        assertThat(toList.kinds).containsExactlyInAnyOrderEntriesOf(mapOf(DependencyKind.CALLS to 3, DependencyKind.RETURNS to 2))
        assertThat(toList.extends).isFalse
        assertThat(toList.other).isTrue
        assertThat(toList.weight).isEqualTo(5)
    }

    @Test
    fun `a structural edge coexisting with a non-structural ref sets both extends and other`() {
        // Documents the spec's open 'other'-flag divergence.
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyType(
            "app",
            "com.foo.A",
            refs = listOf(
                Ref("com.foo.B", DependencyKind.EXTENDS, count = 1),
                Ref("com.foo.B", DependencyKind.CALLS, count = 5),
            ),
        )

        val toB = store.typeDependencies().single { it.toFqn == "com.foo.B" }
        assertThat(toB.extends).isTrue
        assertThat(toB.other).isTrue
        assertThat(toB.weight).isEqualTo(6)
    }

    @Test
    fun `only the type's own annotations are structural, a member's annotation is another use`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyType(
            "app",
            "com.foo.A",
            refs = listOf(
                Ref("com.foo.TypeAnn", DependencyKind.ANNOTATED_BY, count = 1, typeLevel = true),
                Ref("com.foo.Both", DependencyKind.ANNOTATED_BY, count = 1, typeLevel = true),
                Ref("com.foo.Both", DependencyKind.ANNOTATED_BY, count = 2, typeLevel = false),
                Ref("com.foo.MemberAnn", DependencyKind.ANNOTATED_BY, count = 1, typeLevel = false),
            ),
        )

        val deps = store.typeDependencies().associateBy { it.toFqn }
        assertThat(deps.getValue("com.foo.TypeAnn").let { it.annotatedBy to it.other }).isEqualTo(true to false)
        assertThat(deps.getValue("com.foo.Both").let { it.annotatedBy to it.other }).isEqualTo(true to true)
        assertThat(deps.getValue("com.foo.Both").kinds).isEqualTo(mapOf(DependencyKind.ANNOTATED_BY to 3))
        assertThat(deps.getValue("com.foo.MemberAnn").let { it.annotatedBy to it.other }).isEqualTo(false to true)
    }

    @Test
    fun `re-applying a class replaces its owned refs`() {
        val store = GraphStore()
        store.registerArtifact(artifact("app"))
        store.applyType(
            "app",
            "com.foo.A",
            refs = listOf(
                Ref("com.foo.B", DependencyKind.CALLS, count = 1),
                Ref("com.foo.C", DependencyKind.CALLS, count = 1),
            ),
        )
        assertThat(store.typeDependencies().map { it.toFqn }.toList()).containsExactlyInAnyOrder("com.foo.B", "com.foo.C")

        // recompile drops the ref to C
        store.applyType("app", "com.foo.A", refs = listOf(Ref("com.foo.B", DependencyKind.CALLS, count = 1)))
        assertThat(store.typeDependencies().map { it.toFqn }.toList()).containsExactly("com.foo.B")
        assertThat(store.typeDependencies().filter { it.toFqn == "com.foo.C" }.map { it.fromFqn }.toList()).doesNotContain("com.foo.A")
    }

    @Test
    fun `EXCLUDE_UBIQUITOUS filter excludes primitives arrays and ubiquitous types from placeholders`() {
        val store = GraphStore(ExternalTypeFilter.EXCLUDE_UBIQUITOUS)
        store.registerArtifact(artifact("app"))
        store.applyType(
            "app",
            "com.foo.A",
            refs = listOf(
                Ref("int", DependencyKind.CALLS, count = 1),
                Ref("[I", DependencyKind.CALLS, count = 1),
                Ref("Task", DependencyKind.CALLS, count = 1),            // default package (no dot)
                Ref("java.lang.String", DependencyKind.CALLS, count = 1),
                Ref("kotlin.Unit", DependencyKind.CALLS, count = 1),
                Ref("com.other.Lib", DependencyKind.CALLS, count = 1),   // normal external -> placeholder
            ),
        )
        // excluded targets: no placeholder node, and no external package chain
        assertThat(store.typeByFqn("int")).isNull()
        assertThat(store.typeByFqn("[I")).isNull()
        assertThat(store.typeByFqn("Task")).isNull()
        assertThat(store.typeByFqn("java.lang.String")).isNull()
        assertThat(store.typeByFqn("kotlin.Unit")).isNull()
        assertThat(store.nodes().map { it.fqn }).doesNotContain("java.lang", "kotlin")

        // a normal external type still becomes a placeholder
        assertThat(store.typeByFqn("com.other.Lib")!!.placeholder).isTrue

        // the refs are still facts: every target remains a type dependency even without a node
        assertThat(store.typeDependencies().map { it.toFqn }.toList())
            .contains("int", "[I", "Task", "java.lang.String", "kotlin.Unit", "com.other.Lib")
    }
}
