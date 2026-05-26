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
package io.hierograph.hierarchicalgraph.core.model

interface HGCoreDependency {
    val from: HGNode
    val to: HGNode
    val type: String
    var weight: Int
    var attributesBitmap: Int
    val dependencySource: IDependencySource
    val rootNode: HGRootNode

    fun <T : Any> getDependencySource(clazz: Class<T>): T?
}
