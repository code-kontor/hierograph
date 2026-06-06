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
package io.hierograph.hierarchicalgraph.core.model.internal

import io.hierograph.hierarchicalgraph.core.model.CoreDependency
import io.hierograph.hierarchicalgraph.core.model.CoreNode
import io.hierograph.hierarchicalgraph.core.model.IDependencySource

class CoreDependencyImpl(
    override val from: CoreNode,
    override val to: CoreNode,
    override val type: String,
    override val dependencySource: IDependencySource,
) : CoreDependency {
    override var weight: Int = 1
    override var attributesBitmap: Int = 0

    override fun <T : Any> getDependencySource(clazz: Class<T>): T? =
        if (clazz.isInstance(dependencySource)) clazz.cast(dependencySource) else null
}
