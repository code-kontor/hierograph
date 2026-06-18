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
package io.hierograph.mcp.jqa.hierarchicalgraph

import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.MappingProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.MappingProviderMetadata
import io.hierograph.mcp.jqa.hierarchicalgraph.fwk.MappingDrivenDependencyProvider
import io.hierograph.mcp.jqa.hierarchicalgraph.fwk.MappingDrivenHierarchyProvider

/**
 * Wires the declarative [jQAssistantMapping] to the generic mapping-driven adapters, producing the
 * SPI [MappingProvider] consumed by the mapping service.
 *
 * The two sides are deliberately separate: [jQAssistantMapping] holds the jQAssistant/Java domain
 * knowledge, the `fwk` adapters hold the SPI/bolt execution machinery, and the only adapter-specific
 * binding here is the [ExtendedGraphDbNodeSource] factory that carries name/fqn through the graph.
 */
fun jQAssistantMappingProvider() = MappingProvider(
    MappingProviderMetadata(
        identifier = "io.hierograph.jqassistant.hierarchicalgraph",
        name = "Hierograph jQAssistant (hierarchical packages)"
    ),
    MappingDrivenHierarchyProvider(
        jQAssistantMapping.topLevelRules,
        jQAssistantMapping.parentChildRules,
    ) { id, name, fqn -> ExtendedGraphDbNodeSource(id, name, fqn) },
    MappingDrivenDependencyProvider(jQAssistantMapping.dependencyRules),
)
