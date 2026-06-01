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
package io.hierograph.hierarchicalgraph.graphdb.mapping.spi

import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbDependencySource

interface IDependencyDefinitionProvider {
    fun initialize()
    fun dispose()
    val dependencies: List<DependencyDefinition>

    fun createDependencySource(depDef: DependencyDefinition): GraphDbDependencySource =
        GraphDbDependencySource(identifier = depDef.idRel, type = depDef.type)
}

data class DependencyDefinition(
    val idStart: Long,
    val idTarget: Long,
    val idRel: Long,
    val type: String,
    val weight: Int = 1,
    val attributesBitmap: Int = 0
)