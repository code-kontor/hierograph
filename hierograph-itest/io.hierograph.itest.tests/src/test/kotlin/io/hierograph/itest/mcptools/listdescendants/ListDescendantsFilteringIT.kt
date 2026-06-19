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
package io.hierograph.itest.mcptools.listdescendants

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * `list_descendants` — result filtering: how `kind_filter` (2nd argument) and `name_pattern`
 * (3rd argument) narrow the matched descendants, including the distinction between the two
 * (a common call-shape confusion).
 */
class ListDescendantsFilteringIT : AbstractListDescendantsIT() {

    @Test
    fun `filtering a subtree to packages returns the nested subpackages`() {
        val response = listDescendantsTool.listDescendants(
            nodeId = classloadingPackageId(),
            kindFilter = listOf("packages"),
            namePattern = null,
            modifierFilter = null,
            limit = null,
            cursor = null
        )

        @Suppress("UNCHECKED_CAST")
        val results = response["results"] as List<Map<String, Any?>>
        assertThat(results.map { it["qualified_name"] }).containsExactlyInAnyOrder(
            "$CLASSLOADING_PKG_FQN.glassfish",
            "$CLASSLOADING_PKG_FQN.jboss",
            "$CLASSLOADING_PKG_FQN.tomcat"
        )
        assertThat(results.map { it["kind"] }).allMatch { it == "java.package" }
    }

    @ParameterizedTest(name = "subtree kind_filter [{0}] -> {1} matches")
    @CsvSource(
        value = [
            "types          | 19",
            "java.class     | 14",
            "java.interface |  5",
            "members        | 123",
            "java.method    | 85",
            "java.field     | 38",
            "packages       |  3",
            "java.module    |  0",
        ],
        delimiter = '|'
    )
    fun `kind_filter narrows the matched descendants of a subtree`(kind: String, expectedTotal: Int) {
        val response = listDescendantsTool.listDescendants(
            nodeId = classloadingPackageId(),
            kindFilter = listOf(kind.trim()),
            namePattern = null,
            modifierFilter = null,
            limit = null,
            cursor = null
        )

        @Suppress("UNCHECKED_CAST")
        val summary = response["summary"] as Map<String, Any?>
        assertThat(summary["total"]).isEqualTo(expectedTotal)
        // Every expected count is below the default page size, so nothing is truncated.
        assertThat(summary["truncated"]).isEqualTo(false)

        @Suppress("UNCHECKED_CAST")
        val results = response["results"] as List<Map<String, Any?>>
        assertThat(results).hasSize(expectedTotal)
    }

    @Test
    fun `the types filter on the beans package never returns fields or methods`() {
        // 'org.springframework.beans' occurs in more than one module; every occurrence must honour
        // the 'types' filter. No limit is set, so the default page applies and the result is
        // paginated — but neither the returned page nor the (whole-subtree) by_kind summary may
        // contain a field or method.
        val beansPackageIds = findPackageNodes("org.springframework.beans")
        assertThat(beansPackageIds).isNotEmpty

        for (nodeId in beansPackageIds) {
            val response = listDescendantsTool.listDescendants(
                nodeId = nodeId,
                kindFilter = listOf("types"),
                namePattern = null,
                modifierFilter = null,
                limit = null,
                cursor = null
            )

            @Suppress("UNCHECKED_CAST")
            val results = response["results"] as List<Map<String, Any?>>
            assertThat(results.map { it["kind"] })
                .`as`("returned kinds for beans node %s", nodeId)
                .isSubsetOf(TYPE_KINDS)

            // by_kind is computed over the full (pre-pagination) match set, so it proves that
            // nothing non-type leaked into any page — not just the first one.
            @Suppress("UNCHECKED_CAST")
            val byKind = (response["summary"] as Map<String, Any?>)["by_kind"] as Map<String, Any?>
            assertThat(byKind.keys)
                .`as`("by_kind for beans node %s", nodeId)
                .isSubsetOf(TYPE_KINDS)
        }
    }

    @Test
    fun `passing the filter as the name pattern matches member names, not kinds`() {
        // Reproduces a call-shape confusion: 'types' supplied as the *name pattern* (3rd argument)
        // instead of the *kind filter* (2nd argument). The tool then matches descendants whose NAME
        // contains 'types' — which legitimately includes fields and methods (e.g. 'parameterTypes',
        // 'setSupportedTypes'). This is correct name-matching behaviour, NOT a kind-filter leak.
        val beansId = largestPackageNode("org.springframework.beans")

        val response = listDescendantsTool.listDescendants(
            nodeId = beansId,
            kindFilter = null,
            namePattern = "types",
            modifierFilter = null,
            limit = null,
            cursor = null
        )

        @Suppress("UNCHECKED_CAST")
        val results = response["results"] as List<Map<String, Any?>>
        assertThat(results).isNotEmpty
        assertThat(results.map { it["name"] as String }).allMatch { it.lowercase().contains("types") }
        // Members appear precisely because the match is on name, not kind.
        assertThat(results.map { it["kind"] }).contains("java.field", "java.method")
    }
}
