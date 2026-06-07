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

import io.hierograph.hierarchicalgraph.core.model.HGGraph
import io.hierograph.hierarchicalgraph.core.model.HGNode

class HGGraphImpl : HGGraph {
    private val nodeMap: MutableMap<Any, HGNode> = mutableMapOf()
    private val extensionRegistry: MutableMap<String, Any> = mutableMapOf()

    override val nodes: Collection<HGNode> get() = nodeMap.values

    override fun lookupNode(identifier: Any): HGNode? = nodeMap[identifier]

    internal fun registerNode(node: HGNode) {
        nodeMap[node.identifier] = node
    }

    override fun <T : Any> registerExtension(clazz: Class<T>, extension: T) {
        extensionRegistry[clazz.name] = extension
    }

    override fun registerExtension(key: String, extension: Any) {
        extensionRegistry[key] = extension
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getExtension(clazz: Class<T>): T? =
        extensionRegistry[clazz.name] as? T

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getExtension(key: String, clazz: Class<T>): T? {
        val value = extensionRegistry[key] ?: return null
        check(clazz.isAssignableFrom(value.javaClass)) {
            "Extension under key '$key' is ${value.javaClass.name}, not assignable to ${clazz.name}"
        }
        return value as T
    }

    override fun <T : Any> hasExtension(clazz: Class<T>): Boolean =
        extensionRegistry.containsKey(clazz.name)
}
