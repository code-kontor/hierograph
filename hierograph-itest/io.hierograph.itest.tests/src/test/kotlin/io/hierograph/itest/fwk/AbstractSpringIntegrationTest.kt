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
 * Base for Spring-based integration tests: wires the running container's Bolt endpoint into the
 * Spring [org.springframework.core.env.Environment]. It deliberately carries no `@SpringBootTest`
 * itself — a subclass declares which part of the application to boot (e.g.
 * [AbstractHierarchicalGraphIntegrationTest]).
 *
 * Keeping the `@DynamicPropertySource` here (rather than per test class) means every subclass
 * shares an identical context configuration, so Spring's test-context cache creates the
 * [org.springframework.context.ApplicationContext] only once for the whole run.
 */
abstract class AbstractSpringIntegrationTest : AbstractIntegrationTest() {

    companion object {

        @JvmStatic
        @DynamicPropertySource
        fun boltProperties(registry: DynamicPropertyRegistry) {
            // Ensure the container is up before the Spring context reads the property
            // (start() is idempotent; the shared lifecycle is owned by HierographImageExtension).
            HierographImageContainer.instance.start()
            registry.add("hierograph.bolt.uri") { HierographImageContainer.boltUri }
        }
    }
}
