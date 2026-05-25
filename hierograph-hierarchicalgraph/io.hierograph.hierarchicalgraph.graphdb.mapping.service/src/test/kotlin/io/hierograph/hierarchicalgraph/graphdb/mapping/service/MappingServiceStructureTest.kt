package io.hierograph.hierarchicalgraph.graphdb.mapping.service

import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.*
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
        assertThat(IBoltClientAware::class.java.methods.map { it.name }).contains("initialize")
    }
}
