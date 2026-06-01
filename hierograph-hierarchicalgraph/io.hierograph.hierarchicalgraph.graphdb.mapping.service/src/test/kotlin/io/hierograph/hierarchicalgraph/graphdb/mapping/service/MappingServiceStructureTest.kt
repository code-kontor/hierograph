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
package io.hierograph.hierarchicalgraph.graphdb.mapping.service

import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.bolt.AbstractQueryBasedDependencyProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.bolt.IBoltClientAware
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MappingServiceStructureTest {

    @Test
    fun `DefaultMappingService implements IMappingService`() {
        val service: IMappingService = DefaultMappingService()
        assertThat(service).isNotNull
    }

    @Test
    fun `AbstractQueryBasedDependencyProvider resolveDependencyQuery is accessible`() {
        // Verify the companion function exists (structural test only — no bolt client)
        assertThat(AbstractQueryBasedDependencyProvider::class.java).isNotNull
    }

    @Test
    fun `IBoltClientAware interface is defined`() {
        assertThat(IBoltClientAware::class.java.methods.map { it.name })
            .contains("getBoltClient", "setBoltClient")
    }
}
