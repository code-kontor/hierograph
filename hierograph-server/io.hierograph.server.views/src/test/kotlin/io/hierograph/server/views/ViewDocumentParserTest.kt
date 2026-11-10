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
package io.hierograph.server.views

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test

class ViewDocumentParserTest {

    private fun problems(json: String): List<String> =
        catchThrowableOfType(ViewDocumentException::class.java) { ViewDocumentParser.parse(json) }.problems

    @Test
    fun `parses the multi-module example of the design`() {
        val document = ViewDocumentParser.parse(
            """
            {
              "name": "multi-modules",
              "description": "The Maven aggregators of the reactor",
              "groups": [
                {
                  "name": "hierograph-server",
                  "members": [
                    "io.hierograph:io.hierograph.server:*",
                    "io.hierograph:io.hierograph.server.*:*"
                  ]
                },
                {
                  "name": "core",
                  "description": "Everything the server builds on",
                  "groups": [
                    { "name": "hierograph-graphstore",
                      "members": ["io.hierograph:io.hierograph.graphstore.*:*"] },
                    { "name": "hierograph-hierarchicalgraph",
                      "members": [{ "match": "io.hierograph:io.hierograph.hierarchicalgraph.*:*", "kind": "java.module" }] }
                  ]
                }
              ]
            }
            """,
        )

        assertThat(document).isEqualTo(
            ViewDocument(
                name = "multi-modules",
                description = "The Maven aggregators of the reactor",
                groups = listOf(
                    GroupDefinition(
                        "hierograph-server",
                        members = listOf(
                            MemberSelector("io.hierograph:io.hierograph.server:*"),
                            MemberSelector("io.hierograph:io.hierograph.server.*:*"),
                        ),
                    ),
                    GroupDefinition(
                        "core",
                        description = "Everything the server builds on",
                        groups = listOf(
                            GroupDefinition("hierograph-graphstore", members = listOf(MemberSelector("io.hierograph:io.hierograph.graphstore.*:*"))),
                            GroupDefinition(
                                "hierograph-hierarchicalgraph",
                                members = listOf(MemberSelector("io.hierograph:io.hierograph.hierarchicalgraph.*:*", kind = "java.module")),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `description, groups and members default to absent and empty`() {
        assertThat(ViewDocumentParser.parse("""{ "name": "empty" }""")).isEqualTo(ViewDocument("empty"))
        assertThat(ViewDocumentParser.parse("""{ "name": "v", "groups": [ { "name": "g" } ] }""").groups.single())
            .isEqualTo(GroupDefinition("g"))
    }

    @Test
    fun `selector objects take kind aliases and within`() {
        val selector = ViewDocumentParser.parse(
            """{ "name": "v", "groups": [ { "name": "g", "members": [
                 { "match": "a.*Watcher", "kind": "types", "within": "acme:app:*" } ] } ] }""",
        ).groups.single().members.single()

        assertThat(selector).isEqualTo(MemberSelector("a.*Watcher", kind = "types", within = "acme:app:*"))
    }

    @Test
    fun `invalid JSON is reported with its location`() {
        assertThat(problems("""{ "name": "v", """)).singleElement().asString()
            .startsWith("Invalid JSON at line 1")
    }

    @Test
    fun `the document must be an object with a name`() {
        assertThat(problems("[]")).containsExactly("the document must be a JSON object")
        assertThat(problems("{}")).containsExactly("name: required")
        assertThat(problems("""{ "name": 3 }""")).containsExactly("name: must be a string")
    }

    @Test
    fun `view names are lower-case and start with a letter or digit`() {
        assertThat(problems("""{ "name": "Multi" }""")).singleElement().asString().contains("not a valid view name")
        assertThat(problems("""{ "name": "-x" }""")).hasSize(1)
        assertThat(problems("""{ "name": "a:b" }""")).hasSize(1)
        assertThat(ViewDocumentParser.parse("""{ "name": "target-2027.v_1" }""").name).isEqualTo("target-2027.v_1")
    }

    @Test
    fun `unknown fields are rejected, restructuring fields with their own message`() {
        assertThat(problems("""{ "name": "v", "group": [] }""")).singleElement().asString().startsWith("group: unknown field")
        assertThat(problems("""{ "name": "v", "restructure": true, "moves": [] }"""))
            .containsExactly(
                "restructure: restructuring views are not supported yet",
                "moves: restructuring views are not supported yet",
            )
        assertThat(problems("""{ "name": "v", "groups": [ { "name": "g", "member": [] } ] }"""))
            .singleElement().asString().startsWith("groups[0].member: unknown field")
        assertThat(problems("""{ "name": "v", "groups": [ { "name": "g", "members": [ { "match": "a", "kinds": "types" } ] } ] }"""))
            .singleElement().asString().startsWith("groups[0].members[0].kinds: unknown field")
    }

    @Test
    fun `group names are required, not blank, free of slashes and unique among siblings`() {
        assertThat(
            problems(
                """{ "name": "v", "groups": [
                     { "members": ["a"] },
                     { "name": " " },
                     { "name": "a/b" },
                     { "name": "core", "groups": [ { "name": "x" }, { "name": "x" } ] },
                     { "name": "core" }
                   ] }""",
            ),
        ).containsExactly(
            "groups[0].name: required",
            "groups[1].name: a group name must not be blank",
            "groups[2].name: a group name must not contain '/'",
            "groups[3].groups: the group name 'x' is used more than once among sibling groups",
            "groups: the group name 'core' is used more than once among sibling groups",
        )
    }

    @Test
    fun `the same name in different branches is allowed`() {
        val document = ViewDocumentParser.parse(
            """{ "name": "v", "groups": [ { "name": "a", "groups": [ { "name": "api" } ] }, { "name": "b", "groups": [ { "name": "api" } ] } ] }""",
        )
        assertThat(document.groups.map { it.groups.single().name }).containsExactly("api", "api")
    }

    @Test
    fun `selectors are checked for form, pattern and kind`() {
        assertThat(
            problems(
                """{ "name": "v", "groups": [ { "name": "g", "members": [
                     3,
                     "a.{b",
                     { "kind": "types" },
                     { "match": "a", "kind": "java.klass" },
                     { "match": "a", "within": "x}" }
                   ] } ] }""",
            ),
        ).hasSize(5).satisfiesExactly(
            { assertThat(it).isEqualTo("groups[0].members[0]: a member selector must be a string or a JSON object") },
            { assertThat(it).startsWith("groups[0].members[1]: '{' at position 3 is not closed") },
            { assertThat(it).isEqualTo("groups[0].members[2].match: required") },
            { assertThat(it).startsWith("groups[0].members[3].kind: unknown kind 'java.klass'") },
            { assertThat(it).startsWith("groups[0].members[4].within: '}' without '{'") },
        )
    }

    @Test
    fun `fields of the wrong type are reported`() {
        assertThat(problems("""{ "name": "v", "description": 1, "groups": {} }"""))
            .containsExactly("description: must be a string", "groups: must be an array")
        assertThat(problems("""{ "name": "v", "groups": [ { "name": "g", "members": "a" } ] }"""))
            .containsExactly("groups[0].members: must be an array")
    }
}
