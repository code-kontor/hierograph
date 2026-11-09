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
package io.hierograph.manifest

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * JSON for descriptors and manifests. Writing uses Gson directly. Reading is **lenient** and goes through the
 * JSON tree rather than Gson's reflective binding, which would leave missing fields `null` in non-null Kotlin
 * properties: a descriptor or manifest written by an older producer (single `mainOutputDir`, no `testClasspath`)
 * still reads, with missing lists empty.
 */
internal object ManifestJson {

    val gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()

    fun toJson(value: Any): String = gson.toJson(value) + "\n"

    fun parseObject(text: String): JsonObject? = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()

    /** Reads a module descriptor from [obj], or `null` when it has no `gav`. */
    fun readDescriptor(obj: JsonObject): ModuleDescriptor? {
        val gav = obj.string("gav") ?: return null
        return ModuleDescriptor(
            gav = gav,
            name = obj.string("name") ?: gav,
            mainOutputDirs = obj.strings("mainOutputDirs") ?: listOfNotNull(obj.string("mainOutputDir")),
            testOutputDirs = obj.strings("testOutputDirs") ?: listOfNotNull(obj.string("testOutputDir")),
            sourceRoots = obj.strings("sourceRoots").orEmpty(),
            testSourceRoots = obj.strings("testSourceRoots").orEmpty(),
            classpath = obj.entries("classpath"),
            testClasspath = obj.entries("testClasspath"),
        )
    }

    /** The module descriptors of a manifest tree, skipping unreadable entries. */
    fun readModules(manifest: JsonObject): List<ModuleDescriptor> =
        manifest.array("modules").mapNotNull { (it as? JsonObject)?.let(::readDescriptor) }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.array(key: String): List<JsonElement> = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()

    private fun JsonObject.strings(key: String): List<String>? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }

    private fun JsonObject.entries(key: String): List<ClasspathEntry> = array(key).mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val gav = o.string("gav") ?: return@mapNotNull null
        ClasspathEntry(gav, o.get("internal")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false, o.string("jar"))
    }
}
