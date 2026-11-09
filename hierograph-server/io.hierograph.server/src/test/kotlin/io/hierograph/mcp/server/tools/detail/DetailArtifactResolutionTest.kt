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
package io.hierograph.mcp.server.tools.detail

import io.hierograph.graphstore.jvm.model.ClassFacts
import io.hierograph.graphstore.jvm.model.DetailRef
import io.hierograph.graphstore.jvm.model.JavaKinds
import io.hierograph.graphstore.jvm.model.MemberFacts
import io.hierograph.graphstore.jvm.model.Ref
import io.hierograph.graphstore.jvm.model.RefKind
import io.hierograph.graphstore.jvm.parquet.ParquetWriter
import io.hierograph.hierarchicalgraph.model.NodeHandle
import io.hierograph.mcp.server.tools.NodeRefFactory
import io.hierograph.mcp.server.tools.dependencyanalysis.OutgoingDependenciesTool
import io.hierograph.server.graphservice.HierarchicalGraphService
import io.hierograph.server.graphservice.pagination.DataHashProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Verifies that detail rows resolve their types in the artifact that holds them when two artifacts define
 * the same FQN: `lib-a` and `lib-b` both define `com.example.dup.Twin`, each with a user of its own Twin.
 */
@Suppress("UNCHECKED_CAST")
class DetailArtifactResolutionTest {

    private val twin = "com.example.dup.Twin"

    private fun artifact(key: String, user: String) = listOf(
        ParquetWriter.ArtifactClass(key, ClassFacts(
            twin, JavaKinds.CLASS, 0, listOf(MemberFacts("$twin#void m()", JavaKinds.METHOD, "void m()", "m")),
            emptyList(), "t-$key",
        )),
        ParquetWriter.ArtifactClass(key, ClassFacts(
            user, JavaKinds.CLASS, 0, listOf(MemberFacts("$user#void run()", JavaKinds.METHOD, "void run()", "run")),
            listOf(Ref(user, twin, RefKind.DEPENDS_ON, count = 1)), "u-$key",
        )),
    )

    private val service = HierarchicalGraphService().also {
        val dir = Files.createTempDirectory("hierograph-twins")
        ParquetWriter.writeArtifacts(
            dir,
            artifact("lib-a", "com.example.a.UserA") + artifact("lib-b", "com.example.b.UserB"),
            listOf(
                DetailRef("com.example.a.UserA#void run()", "com.example.a.UserA", "$twin#void m()", twin, "calls", 3),
                DetailRef("com.example.b.UserB#void run()", "com.example.b.UserB", "$twin#void m()", twin, "calls", 7),
            ),
            packaging = ParquetWriter.Packaging.EXPLODED,
        )
        it.seedFromParquet(dir)
    }
    private val tool = OutgoingDependenciesTool(
        service, NodeRefFactory(service), ParquetDetailDependencies(service, DataHashProvider(service)), DataHashProvider(service),
    )

    private fun typeId(artifact: String, fqn: String) = NodeHandle.of(service.graphStore!!.typeKey(artifact, fqn))
    private fun rows(fromId: Long, toId: Long) =
        tool.outgoingDependencies(fromId, toId, "detail", null, null, null)["edges"] as List<Map<String, Any?>>

    @Test
    fun `a call resolves to the definition its own artifact sees`() {
        val userA = typeId("lib-a", "com.example.a.UserA")
        val toOwnTwin = rows(userA, typeId("lib-a", twin))
        assertThat(toOwnTwin).hasSize(1)
        assertThat(toOwnTwin.single()["to_parent"]).isEqualTo(typeId("lib-a", twin))
        assertThat(rows(userA, typeId("lib-b", twin))).isEmpty()
    }

    @Test
    fun `a type's rows are its own, not those of a same-FQN twin`() {
        // From lib-b's Twin, nothing is called; From lib-b's user, only its own call is listed.
        val rowsB = rows(typeId("lib-b", "com.example.b.UserB"), typeId("lib-b", twin))
        assertThat(rowsB.map { (it["location"] as Map<String, Any?>)["line_number"] }).containsExactly(7L)
    }
}
