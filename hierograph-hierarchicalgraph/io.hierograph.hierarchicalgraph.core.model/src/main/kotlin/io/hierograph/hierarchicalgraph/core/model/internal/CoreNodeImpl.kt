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
import io.hierograph.hierarchicalgraph.core.model.INodeSource

class CoreNodeImpl(
    override val nodeSource: INodeSource,
) : CoreNode {
    override var kind: Any? = null

    override val identifier: Any get() = nodeSource.identifier

    internal val _outgoing: MutableList<CoreDependency> = mutableListOf()
    internal val _incoming: MutableList<CoreDependency> = mutableListOf()

    override val outgoingCoreDependencies: List<CoreDependency> get() = _outgoing
    override val incomingCoreDependencies: List<CoreDependency> get() = _incoming

    override fun <T : Any> getNodeSource(clazz: Class<T>): T? =
        if (clazz.isInstance(nodeSource)) clazz.cast(nodeSource) else null
}
