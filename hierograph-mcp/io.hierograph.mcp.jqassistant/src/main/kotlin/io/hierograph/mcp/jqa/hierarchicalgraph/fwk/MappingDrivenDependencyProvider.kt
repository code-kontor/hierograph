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
package io.hierograph.mcp.jqa.hierarchicalgraph.fwk

import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.DependencyDefinition
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinitionProvider
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.bolt.AbstractBoltClientAware
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.bolt.AbstractQueryBasedDependencyProvider

/**
 * Generic [IDependencyDefinitionProvider] that executes a declarative set of dependency rules.
 *
 * Domain-agnostic counterpart to [MappingDrivenHierarchyProvider]: it runs each rule's Cypher and
 * resolves the records into [DependencyDefinition]s (reusing the SPI's shimmable column/bitmap
 * convention). All knowledge of *what* is being mapped lives in the [GraphDbMapping] rules.
 */
class MappingDrivenDependencyProvider(
    private val dependencyRules: List<DependencyRule>,
) : IDependencyDefinitionProvider, AbstractBoltClientAware() {

    override var dependencies: List<DependencyDefinition> = emptyList()
        private set

    override fun initialize() {
        dependencies = dependencyRules.flatMap { rule ->
            AbstractQueryBasedDependencyProvider.resolveDependencyQuery(boltClient, rule.cypher)
        }
    }

    override fun dispose() {
        dependencies = emptyList()
    }
}
