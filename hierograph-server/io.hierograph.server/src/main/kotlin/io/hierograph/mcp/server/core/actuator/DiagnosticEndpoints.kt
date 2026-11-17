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

import org.springframework.boot.actuate.endpoint.EndpointFilter
import org.springframework.boot.actuate.endpoint.EndpointId
import org.springframework.boot.actuate.endpoint.web.ExposableWebEndpoint
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The diagnostic actuator endpoints — `env`, `heapdump` and `threaddump` — behind one switch,
 * `hierograph.actuator.diagnostics.enabled` (default `false`: they expose the server's configuration, threads and
 * entire heap, so they are switched on only for a diagnosis).
 *
 * `management.endpoints.web.exposure.include` lists them together with `health`, `info` and `metrics`; unless the
 * switch is `true`, [DiagnosticEndpointsFilter] removes them from the web endpoints, so they are neither served nor
 * listed under `/actuator`. The other endpoints are unaffected.
 */
@Configuration(proxyBeanMethods = false)
class DiagnosticEndpointsConfiguration {

    @Bean
    @ConditionalOnProperty(name = ["hierograph.actuator.diagnostics.enabled"], havingValue = "false", matchIfMissing = true)
    fun diagnosticEndpointsFilter(): EndpointFilter<ExposableWebEndpoint> = DiagnosticEndpointsFilter()
}

/** Rejects the diagnostic endpoints ([DIAGNOSTIC_ENDPOINTS]) and accepts every other web endpoint. */
class DiagnosticEndpointsFilter : EndpointFilter<ExposableWebEndpoint> {

    override fun match(endpoint: ExposableWebEndpoint): Boolean = endpoint.endpointId !in DIAGNOSTIC_ENDPOINTS

    companion object {
        /** The endpoints the switch controls. */
        val DIAGNOSTIC_ENDPOINTS: Set<EndpointId> = setOf("env", "heapdump", "threaddump").map(EndpointId::of).toSet()
    }
}
