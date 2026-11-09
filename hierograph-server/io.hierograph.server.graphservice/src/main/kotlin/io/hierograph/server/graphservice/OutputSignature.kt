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
package io.hierograph.server.graphservice

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Order-independent fold of `(path, size, mtime)` over the `.class` files under [dir]; `0` when [dir] does not
 * exist. Cheap (it only `stat`s files), so it serves both the [PollingGraphWatcher]'s change detection and the
 * check whether a cached [ManifestGraphSource.ModuleScan] still matches its output directory.
 */
internal fun outputSignature(dir: Path): Long {
    if (!Files.isDirectory(dir)) return 0L
    var sig = 0L
    runCatching {
        Files.walk(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".class") }.forEach { p ->
                val attrs = runCatching { Files.readAttributes(p, BasicFileAttributes::class.java) }.getOrNull()
                    ?: return@forEach
                sig += p.toString().hashCode().toLong() * 1_099_511_628_211L + attrs.size() * 31L +
                    attrs.lastModifiedTime().toMillis()
            }
        }
    }
    return sig
}
