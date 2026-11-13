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
package io.hierograph.itest.graphstore

import io.hierograph.graphstore.jvm.archive.model.DependencyKind
import io.hierograph.graphstore.jvm.archive.model.Dependency
import io.hierograph.graphstore.jvm.archive.ParquetReader
import io.hierograph.itest.fwk.SpringFixture
import io.hierograph.graphstore.jvm.archive.model.JavaNodeKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The full member-level detail vocabulary, verified on real bytecode across the 21-jar Spring snapshot:
 * every relationship kind the scanner emits is present, `throws` (declared) and `raises` (body-thrown)
 * are distinct, and members carry their ordered parameters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DetailEdgeVocabularyIT {

    private val dir = SpringFixture.directory
    private val reader = ParquetReader()
    private lateinit var all: List<Dependency>

    @BeforeAll
    fun load() {
        all = reader.readDependencies(dir)
    }

    @Test
    fun `every scanner-emitted detail relationship is present`() {
        val byRel = all.groupingBy { it.kind.value }.eachCount()
        // Every kind a class file can carry; the derived ones (overrides, read_by, written_by) never appear.
        for (rel in DependencyKind.entries.filterNot { it.derived }.map { it.value }) {
            assertThat(byRel[rel]).describedAs(rel).isNotNull.matches { it!! > 0 }
        }
        // overrides is derived at load (HierarchyResolution), never emitted directly by the scanner.
        assertThat(byRel["overrides"]).describedAs("overrides must not be a direct edge").isNull()
    }

    @Test
    fun `throws is the declared clause and raises is the body-thrown set, and some method has both`() {
        val throwsFrom = all.filter { it.kind == DependencyKind.THROWS }.map { it.fromKey }.toSet()
        val raisesFrom = all.filter { it.kind == DependencyKind.RAISES }.map { it.fromKey }.toSet()
        assertThat(throwsFrom).isNotEmpty
        assertThat(raisesFrom).isNotEmpty
        // A method that declares `throws X` and also does `throw new Y(...)` appears in both.
        assertThat(throwsFrom.intersect(raisesFrom)).describedAs("methods with both throws and raises").isNotEmpty
    }

    @Test
    fun `a method member carries its ordered parameters with resolved types`() {
        // Pick a real method that declares at least one parameter, then read it back with parameters attached.
        val methodWithParams = reader.readAllMembers(dir).first {
            it.kind == JavaNodeKind.METHOD && it.signature.substringAfter('(').substringBefore(')').isNotBlank()
        }
        val full = reader.readMember(dir, methodWithParams.key)
        assertThat(full).isNotNull
        assertThat(full!!.parameters).isNotEmpty
        assertThat(full.parameters).allSatisfy { assertThat(it.typeFqn).isNotBlank() }
        assertThat(full.parameters.map { it.index }).isEqualTo(full.parameters.indices.toList()) // 0..n-1, ordered
    }
}
