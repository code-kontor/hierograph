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

/**
 * `list_descendants` — subtree traversal: full, unfiltered listings that assert the complete shape
 * of a node's subtree (root ref, member/type set, and the `by_kind` summary).
 */
class ListDescendantsTraversalIT : AbstractListDescendantsIT() {

    @Test
    fun `listing the descendants of an interface returns its members`() {
        val response = listDescendants(orderedInterfaceId())

        @Suppress("UNCHECKED_CAST")
        val root = response["root"] as Map<String, Any?>
        assertThat(root["qualified_name"]).isEqualTo(ORDERED_FQN)
        assertThat(root["kind"]).isEqualTo("java.interface")

        // An interface's only descendants are its members (they have no children of their own).
        @Suppress("UNCHECKED_CAST")
        val results = response["results"] as List<Map<String, Any?>>
        assertThat(results.map { it["name"] })
            .containsExactlyInAnyOrder("int HIGHEST_PRECEDENCE", "int LOWEST_PRECEDENCE", "int getOrder()")

        @Suppress("UNCHECKED_CAST")
        val summary = response["summary"] as Map<String, Any?>
        assertThat(summary["total"]).isEqualTo(3)
        assertThat(summary["truncated"]).isEqualTo(false)
        @Suppress("UNCHECKED_CAST")
        val byKind = summary["by_kind"] as Map<String, Any?>
        assertThat(byKind).containsEntry("java.field", 2).containsEntry("java.method", 1)
        assertThat(response).doesNotContainKey("next_cursor")
    }

    @Test
    fun `listing the descendants of a package traverses the whole subtree`() {
        val response = listDescendants(tomcatPackageId())

        @Suppress("UNCHECKED_CAST")
        val root = response["root"] as Map<String, Any?>
        assertThat(root["qualified_name"]).isEqualTo(TOMCAT_PKG_FQN)
        assertThat(root["kind"]).isEqualTo("java.package")

        // The subtree spans the single type plus all of its members.
        @Suppress("UNCHECKED_CAST")
        val results = response["results"] as List<Map<String, Any?>>
        assertThat(results.map { it["qualified_name"] }).contains(
            "$TOMCAT_PKG_FQN.TomcatLoadTimeWeaver",
            "$TOMCAT_PKG_FQN.package-info"
        )

        @Suppress("UNCHECKED_CAST")
        val summary = response["summary"] as Map<String, Any?>
        assertThat(summary["total"]).isEqualTo(11)
        @Suppress("UNCHECKED_CAST")
        val byKind = summary["by_kind"] as Map<String, Any?>
        assertThat(byKind).containsExactlyInAnyOrderEntriesOf(
            mapOf("java.class" to 1, "java.field" to 4, "java.method" to 5, "java.interface" to 1)
        )
    }
}
