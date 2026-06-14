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
package io.hierograph.itest

import io.hierograph.itest.support.AbstractIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Placeholder integration test. Replace with real integration tests as they are added; it only
 * exists so the shared container is wired up and the `itest` profile has something to execute.
 */
class SmokeIT : AbstractIntegrationTest() {

    @Test
    fun `the jqassistant server answers Bolt queries`() {
        container.openDriver().use { driver ->
            // Throws if the server is not actually reachable over Bolt.
            driver.verifyConnectivity()
            driver.session().use { session ->
                val answer = session.run("RETURN 1 AS n").single()["n"].asInt()
                assertThat(answer).isEqualTo(1)
            }
        }
    }
}
