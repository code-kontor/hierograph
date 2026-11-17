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
package io.hierograph.mcp.server.core.actuator

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.endpoint.Access
import org.springframework.boot.actuate.endpoint.EndpointId
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint
import org.springframework.boot.actuate.endpoint.web.WebOperation

class DiagnosticEndpointsFilterTest {

    private val filter = DiagnosticEndpointsFilter()

    @Test
    fun `rejects the diagnostic endpoints`() {
        listOf("env", "heapdump", "threaddump").forEach { id ->
            assertThat(filter.match(endpoint(id))).describedAs(id).isFalse()
        }
    }

    @Test
    fun `accepts every other endpoint`() {
        listOf("health", "info", "metrics").forEach { id ->
            assertThat(filter.match(endpoint(id))).describedAs(id).isTrue()
        }
    }

    private fun endpoint(id: String): ExposableWebEndpoint = object : ExposableWebEndpoint {
        override fun getEndpointId(): EndpointId = EndpointId.of(id)
        override fun getDefaultAccess(): Access = Access.UNRESTRICTED
        override fun getOperations(): Collection<WebOperation> = emptyList()
        override fun getRootPath(): String = id
    }
}
