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
package io.hierograph.itest.fwk

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Base for Spring-based integration tests: points the server at the shared scanner-produced Spring 7.0.8
 * `.hierograph` snapshot ([SpringFixture]) via `hierograph.graph.parquetDir`. It deliberately carries no
 * `@SpringBootTest` itself — a subclass declares which part of the application to boot (e.g.
 * [AbstractHierarchicalGraphIntegrationTest]).
 *
 * Keeping the `@DynamicPropertySource` here (rather than per test class) means every subclass shares an
 * identical context configuration, so Spring's test-context cache creates the
 * [org.springframework.context.ApplicationContext] only once for the whole run.
 */
abstract class AbstractSpringIntegrationTest {

    companion object {

        @JvmStatic
        @DynamicPropertySource
        fun fixtureProperties(registry: DynamicPropertyRegistry) {
            // These tests ship a pre-built Parquet fixture, so pin the Parquet source explicitly —
            // independent of the production default (which is `manifest`).
            registry.add("hierograph.graph.source") { "parquet" }
            // Build the scanner snapshot (once) before the Spring context reads the property.
            registry.add("hierograph.graph.parquetDir") { SpringFixture.directory.toAbsolutePath().toString() }
        }
    }
}
