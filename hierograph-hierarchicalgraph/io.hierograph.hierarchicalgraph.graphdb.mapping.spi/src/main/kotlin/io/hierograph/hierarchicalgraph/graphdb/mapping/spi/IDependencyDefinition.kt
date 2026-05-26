/*
 * Copyright 2024 Gerd Wuetherich
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

interface IDependencyDefinition {
    val idStart: Long
    val idTarget: Long
    val idRel: Long
    val type: String
    val weight: Int get() = 1
    val attributesBitmap: Int get() = 0
}

data class DefaultDependencyDefinition(
    override val idStart: Long,
    override val idTarget: Long,
    override val idRel: Long,
    override val type: String,
    override val weight: Int = 1,
    override val attributesBitmap: Int = 0
) : IDependencyDefinition
