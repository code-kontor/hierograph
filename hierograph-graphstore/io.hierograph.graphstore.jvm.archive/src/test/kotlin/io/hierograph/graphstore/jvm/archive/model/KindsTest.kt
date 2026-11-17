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
package io.hierograph.graphstore.jvm.archive.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The vocabularies of the archive: [JavaNodeKind] and [DependencyKind]. */
class KindsTest {

    @Test
    fun `a node kind is found by its value and prints as it`() {
        for (kind in JavaNodeKind.entries) {
            assertThat(JavaNodeKind.fromValue(kind.value)).isSameAs(kind)
            assertThat("$kind").isEqualTo(kind.value)
        }
        assertThat(JavaNodeKind.fromValue("CLASS")).isNull()
        assertThat(JavaNodeKind.fromValue(JavaNodeKind.EXTERNAL_TYPE)).isNull()
    }

    @Test
    fun `scanned and external types are type kinds, nothing else is`() {
        assertThat(JavaNodeKind.entries.filter { JavaNodeKind.isTypeKind(it) }).containsExactlyInAnyOrderElementsOf(JavaNodeKind.TYPES)
        assertThat(JavaNodeKind.isTypeKind(JavaNodeKind.EXTERNAL_TYPE)).isTrue()
        assertThat(JavaNodeKind.isTypeKind("java.class")).isFalse()   // the value string is not the kind
        assertThat(JavaNodeKind.isTypeKind(null)).isFalse()
    }

    @Test
    fun `the node kinds are every kind but the pseudo kinds`() {
        assertThat(JavaNodeKind.NODE_KINDS)
            .containsExactlyInAnyOrderElementsOf(JavaNodeKind.entries - JavaNodeKind.PRIMITIVE - JavaNodeKind.CONSTRUCTOR)
        assertThat(JavaNodeKind.NODE_KINDS).containsAll(JavaNodeKind.TYPES).containsAll(JavaNodeKind.MEMBERS)
    }

    @Test
    fun `a dependency kind is found by its value, an unknown value is not`() {
        for (kind in DependencyKind.entries) assertThat(DependencyKind.fromValue(kind.value)).isSameAs(kind)
        assertThat(DependencyKind.fromValue("depends_on")).isNull()
        assertThat(DependencyKind.fromValue("CALLS")).isNull()
    }

    @Test
    fun `every kind but raises and the derived ones is counted, in vocabulary order`() {
        assertThat(DependencyKind.AGGREGATED)
            .isEqualTo(DependencyKind.entries - DependencyKind.RAISES - DependencyKind.OVERRIDES - DependencyKind.READ_BY - DependencyKind.WRITTEN_BY)
        assertThat(DependencyKind.entries.filter { it.derived })
            .containsExactly(DependencyKind.OVERRIDES, DependencyKind.READ_BY, DependencyKind.WRITTEN_BY)
        assertThat(DependencyKind.entries.filter { it.derived }).noneMatch { it.counted }
        assertThat(DependencyKind.STRUCTURAL)
            .containsExactlyInAnyOrder(DependencyKind.EXTENDS, DependencyKind.IMPLEMENTS, DependencyKind.ANNOTATED_BY)
            .allMatch { it.counted }
    }

    @Test
    fun `toMap names the kinds by value, in vocabulary order, without zeros`() {
        val map = DependencyKind.toMap(
            mapOf(DependencyKind.CALLS to 3, DependencyKind.EXTENDS to 1, DependencyKind.CASTS to 0, DependencyKind.HAS_TYPE to 2),
        )

        assertThat(map.entries.map { it.key to it.value }).containsExactly("extends" to 1, "has_type" to 2, "calls" to 3)
        assertThat(DependencyKind.toMap(emptyMap())).isEmpty()
    }
}
