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

import io.hierograph.hierarchicalgraph.core.model.*

class HGRootNodeImpl(
    kind: Any?,
    nodeSource: INodeSource
) : HGNodeImpl(kind, nodeSource), HGRootNode {

    override var name: String? = null

    private val _extensionRegistry: MutableMap<String, Any> = mutableMapOf()
    private var _idToNodeMap: MutableMap<Any, HGNode>? = null

    // -- HGRootNode overrides --

    override val predecessors: List<HGNode> get() = emptyList()
    override val rootNode: HGRootNode get() = this

    // -- extension registry --

    override fun <T : Any> registerExtension(clazz: Class<T>, extension: T) {
        _extensionRegistry[clazz.name] = extension
    }

    override fun registerExtension(key: String, extension: Any) {
        _extensionRegistry[key] = extension
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getExtension(clazz: Class<T>): T? {
        return _extensionRegistry[clazz.name] as? T
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getExtension(key: String, clazz: Class<T>): T? {
        val value = _extensionRegistry[key] ?: return null
        check(clazz.isAssignableFrom(value.javaClass)) {
            "Extension under key '$key' is ${value.javaClass.name}, not assignable to ${clazz.name}"
        }
        return value as T
    }

    override fun <T : Any> hasExtension(clazz: Class<T>): Boolean {
        return _extensionRegistry.containsKey(clazz.name)
    }

    override fun <T : Any> hasExtension(key: String, clazz: Class<T>): Boolean {
        val value = _extensionRegistry[key] ?: return false
        return clazz.isAssignableFrom(value.javaClass)
    }

    // -- lookup --

    override fun lookupNode(identifier: Any): HGNode? {
        if (_idToNodeMap == null) {
            _idToNodeMap = mutableMapOf()
            traverseChildren(this) { node ->
                _idToNodeMap!![node.identifier] = node
            }
        }
        return _idToNodeMap!![identifier]
    }

    internal fun registerNodeInMap(node: HGNode) {
        if (_idToNodeMap == null) _idToNodeMap = mutableMapOf()
        _idToNodeMap!![node.identifier] = node
    }

    private fun traverseChildren(node: HGNode, action: (HGNode) -> Unit) {
        for (child in node.children) {
            action(child)
            traverseChildren(child, action)
        }
    }
}
