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
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.api.Test

class NamePatternTest {

    @ParameterizedTest(name = "''{0}'' matches ''{1}'': {2}")
    @CsvSource(
        delimiter = '|',
        value = [
            // exact
            "io.hierograph:io.hierograph.server:0.2.0-SNAPSHOT | io.hierograph:io.hierograph.server:0.2.0-SNAPSHOT      | true",
            "io.hierograph:io.hierograph.server:0.2.0-SNAPSHOT | io.hierograph:io.hierograph.server:0.2.0-SNAPSHOT:test | false",
            "a.b                                               | aXb                                                    | false",
            // * stops at ':'
            "io.hierograph:io.hierograph.server:*              | io.hierograph:io.hierograph.server:0.2.0-SNAPSHOT      | true",
            "io.hierograph:io.hierograph.server:*              | io.hierograph:io.hierograph.server:0.2.0-SNAPSHOT:test | false",
            "io.hierograph:io.hierograph.server.*:*            | io.hierograph:io.hierograph.server.graphql:0.2.0       | true",
            "io.hierograph:io.hierograph.server.*:*            | io.hierograph:io.hierograph.server:0.2.0               | false",
            "io.hierograph.*                                   | io.hierograph.server.graphservice.Watcher              | true",
            "*Watcher                                          | io.hierograph.server.PollingGraphWatcher               | true",
            "*Watcher                                          | io.hierograph.server.WatcherFactory                    | false",
            // ** crosses ':'
            "io.hierograph:**                                  | io.hierograph:io.hierograph.server:0.2.0-SNAPSHOT:test | true",
            "**:test                                           | io.hierograph:io.hierograph.server:0.2.0-SNAPSHOT:test | true",
            "**:test                                           | io.hierograph:io.hierograph.server:0.2.0-SNAPSHOT      | false",
            // alternatives
            "a.{WatchTargets,BuildFileTracker}                 | a.WatchTargets                                         | true",
            "a.{WatchTargets,BuildFileTracker}                 | a.BuildFileTracker                                     | true",
            "a.{WatchTargets,BuildFileTracker}                 | a.OutputSignature                                      | false",
            "spring-{core,beans}-*.jar                         | spring-beans-7.0.8.jar                                 | true",
            "Foo{,Impl}                                        | Foo                                                    | true",
            "Foo{,Impl}                                        | FooImpl                                                | true",
            "a.{*Watcher,Watch*}                               | a.WatchTargets                                         | true",
            // regex metacharacters are literal
            "a+b(c)                                            | a+b(c)                                                 | true",
            "a.$1                                              | a.$1                                                   | true",
            "Outer$*                                           | Outer\$Inner                                           | true",
        ],
    )
    fun `matches the whole qualified name`(pattern: String, name: String, expected: Boolean) {
        assertThat(NamePattern.compile(pattern).matches(name)).isEqualTo(expected)
    }

    @Test
    fun `a pattern without wildcards or braces is exact`() {
        assertThat(NamePattern.compile("io.hierograph:io.hierograph.server:0.2.0").isExact).isTrue()
        assertThat(NamePattern.compile("a,b").isExact).isTrue()
        assertThat(NamePattern.compile("a.*").isExact).isFalse()
        assertThat(NamePattern.compile("{a,b}").isExact).isFalse()
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "a.{b,{c,d}}  | Nested",
            "a.b}         | without",
            "a.{b,c       | is not closed",
        ],
    )
    fun `malformed patterns are rejected with the position`(pattern: String, message: String) {
        assertThatThrownBy { NamePattern.compile(pattern) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(message)
            .hasMessageContaining("position")
    }

    @Test
    fun `an empty pattern is rejected`() {
        assertThatThrownBy { NamePattern.compile("") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
