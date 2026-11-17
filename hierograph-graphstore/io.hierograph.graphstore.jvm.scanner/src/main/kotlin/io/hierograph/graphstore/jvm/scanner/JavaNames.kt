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
package io.hierograph.graphstore.jvm.scanner

import org.objectweb.asm.Type

/**
 * The naming conventions of a snapshot, which every key (and therefore every node handle) is built from,
 * so they must stay stable: dotted FQNs with `$` for nested types, Java source spelling for
 * types in signatures (`int`, `java.lang.String[]`), member signatures `<return> <name>(<arg>,<arg>)` /
 * `<type> <name>`, and **element types** (not array types) as reference targets.
 */
internal object JavaNames {

    /** `java/util/Map$Entry` → `java.util.Map$Entry`. */
    fun fqn(internalName: String): String = internalName.replace('/', '.')

    /** Java source spelling: `int`, `void`, `java.lang.String[]`, `java.util.Map$Entry`. */
    fun display(type: Type): String = type.className

    /** The element type of an array type, else the type itself — what a reference points to. */
    fun element(type: Type): Type = if (type.sort == Type.ARRAY) type.elementType else type

    /** `void <init>(java.lang.String,int[])`, `byte[] asBytes()` — the member-key signature of a method. */
    fun methodSignature(name: String, descriptor: String): String {
        val method = Type.getMethodType(descriptor)
        return "${display(method.returnType)} $name(${method.argumentTypes.joinToString(",") { display(it) }})"
    }

    /** `byte[] bytes` — the member-key signature of a field. */
    fun fieldSignature(name: String, descriptor: String): String = "${display(Type.getType(descriptor))} $name"

    /** The type named by an instruction operand: an internal name, or an array descriptor (`[Ljava/lang/Object;`). */
    fun operandType(internalNameOrDescriptor: String): Type =
        if (internalNameOrDescriptor.startsWith("[")) Type.getType(internalNameOrDescriptor) else Type.getObjectType(internalNameOrDescriptor)
}
