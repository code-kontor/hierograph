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
package io.hierograph.hierarchicalgraph.serialization.internal

internal fun encodeKind(kind: Any?): KindRef? {
    if (kind == null) return null
    val cls = kind::class.java
    val value = when {
        cls.isEnum -> (kind as Enum<*>).name
        kind is String -> kind
        else -> throw UnsupportedOperationException(
            "Unsupported kind type ${cls.name}; only enums and Strings are supported in v1."
        )
    }
    return KindRef(type = cls.name, value = value)
}

internal fun decodeKind(ref: KindRef?): Any? {
    if (ref == null) return null
    if (ref.type == "java.lang.String") return ref.value
    val cls = Class.forName(ref.type)
    if (cls.isEnum) {
        return cls.enumConstants.firstOrNull { (it as Enum<*>).name == ref.value }
            ?: throw IllegalArgumentException("Enum ${ref.type} has no constant named '${ref.value}'")
    }
    throw UnsupportedOperationException(
        "Cannot decode kind of type ${ref.type}; only enums and Strings are supported in v1."
    )
}
