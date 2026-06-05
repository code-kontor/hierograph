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
package io.hierograph.mcp.server.modulith

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

/** A single Spring Modulith application module and the fully-qualified names of its exposed types. */
data class ModulithModuleInfo(
    val name: String,
    val basePackage: String,
    /** FQNs of all types exposed through the module's named interfaces — already includes propagation. */
    val exposedTypes: Set<String>,
)

/**
 * The authoritative Spring Modulith model, read from the `modulith-model.json` produced by the
 * application's own `ApplicationModules` analysis (see ModulithModelExportTest in the scanned project).
 *
 * This is the source of truth for module boundaries and exposed (named-interface) types — semantics
 * that are impractical to re-derive correctly in jQAssistant Cypher (named-interface propagation,
 * generic type arguments, open modules). Hierograph overlays it onto the structural graph by FQN.
 */
class ModulithModel private constructor(
    /** Modules sorted by base-package length descending, so [moduleOf] resolves the most specific first. */
    private val modulesBySpecificity: List<ModulithModuleInfo>,
) {

    val moduleCount: Int get() = modulesBySpecificity.size

    /** The module owning [typeFqn] (longest matching base package), or `null` if it lies outside any module. */
    fun moduleOf(typeFqn: String): ModulithModuleInfo? =
        modulesBySpecificity.firstOrNull { typeFqn == it.basePackage || typeFqn.startsWith(it.basePackage + ".") }

    companion object {

        /** Reads and parses [file]. Throws if the file cannot be read or is not the expected shape. */
        fun read(file: File): ModulithModel {
            val root = ObjectMapper().readTree(file)
            val modules = root.path("modules").map { module ->
                ModulithModuleInfo(
                    name = module.path("name").asText(),
                    basePackage = module.path("basePackage").asText(),
                    exposedTypes = module.path("exposedTypes").map { it.asText() }.toSet(),
                )
            }
            return ModulithModel(modules.sortedByDescending { it.basePackage.length })
        }
    }
}
