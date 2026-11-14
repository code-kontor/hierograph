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

import io.hierograph.graphstore.jvm.model.DetailRef
import io.hierograph.graphstore.jvm.parquet.ParquetReader
import io.hierograph.itest.fwk.SpringFixture
import io.hierograph.graphstore.jvm.model.JavaNodeKind
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
    private lateinit var all: List<DetailRef>

    @BeforeAll
    fun load() {
        all = reader.readDetailRefs(dir)
    }

    @Test
    fun `every scanner-emitted detail relationship is present`() {
        val byRel = all.groupingBy { it.relationship }.eachCount()
        for (rel in listOf("calls", "reads_field", "writes_field", "throws", "raises", "annotated_by", "returns", "parameter_type", "has_type")) {
            assertThat(byRel[rel]).describedAs(rel).isNotNull.matches { it!! > 0 }
        }
        // overrides is derived at load (HierarchyResolution), never emitted directly by the scanner.
        assertThat(byRel["overrides"]).describedAs("overrides must not be a direct edge").isNull()
    }

    @Test
    fun `throws is the declared clause and raises is the body-thrown set, and some method has both`() {
        val throwsFrom = all.filter { it.relationship == "throws" }.map { it.fromKey }.toSet()
        val raisesFrom = all.filter { it.relationship == "raises" }.map { it.fromKey }.toSet()
        assertThat(throwsFrom).isNotEmpty
        assertThat(raisesFrom).isNotEmpty
        // A method that declares `throws X` and also does `throw new Y(...)` appears in both.
        assertThat(throwsFrom.intersect(raisesFrom)).describedAs("methods with both throws and raises").isNotEmpty
    }

    @Test
    fun `parameter rows are one per parameter, positioned and named like the members' parameters`() {
        val parameterRows = all.filter { it.relationship == "parameter_type" || it.relationship == "parameter_annotated_by" }
        assertThat(parameterRows).allSatisfy {
            assertThat(it.parameterIndex).isNotNull.matches { i -> i!! >= 0 }
            assertThat(it.parameterName).isNotBlank()
        }
        assertThat(all.filter { it.relationship !in setOf("parameter_type", "parameter_annotated_by") })
            .allSatisfy { assertThat(it.parameterIndex).isNull() }
        // Some method takes one type at two positions: two rows to the same target, told apart by position.
        val repeated = all.filter { it.relationship == "parameter_type" }.groupBy { it.fromKey to it.toType }.values
            .first { it.size > 1 }
        assertThat(repeated.map { it.parameterIndex }).doesNotHaveDuplicates()
        // The rows agree with the member's parameters.
        val member = reader.readMember(dir, repeated.first().fromKey)!!
        // (rows are stored in target-type order, so compare them by position)
        assertThat(all.filter { it.fromKey == member.key && it.relationship == "parameter_type" }
            .sortedBy { it.parameterIndex }.map { Triple(it.parameterIndex, it.parameterName, it.toType) })
            .containsExactlyElementsOf(member.parameters.map { Triple(it.index, it.name, it.typeFqn) })
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
