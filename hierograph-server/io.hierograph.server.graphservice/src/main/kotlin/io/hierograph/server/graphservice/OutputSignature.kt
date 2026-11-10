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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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

/**
 * The signature of each module in [dirsByModule], in that order: the sum of [outputSignature] over the module's
 * directories. The walks of different modules run concurrently on [pool]; one module's directories are walked
 * by one thread. Without a [pool], a temporary one of up to one thread per core is used.
 */
internal fun outputSignatures(dirsByModule: List<List<Path>>, pool: ExecutorService? = null): List<Long> {
    fun signature(dirs: List<Path>): Long = dirs.sumOf { outputSignature(it) }
    if (pool != null) return walkOn(pool, dirsByModule, ::signature)
    val n = minOf(Runtime.getRuntime().availableProcessors(), dirsByModule.size)
    if (n <= 1) return dirsByModule.map(::signature)
    val temporary = signaturePool(n)
    try {
        return walkOn(temporary, dirsByModule, ::signature)
    } finally {
        temporary.shutdownNow()
    }
}

/** A pool of [threads] daemon threads for [outputSignatures]. */
internal fun signaturePool(threads: Int = Runtime.getRuntime().availableProcessors()): ExecutorService {
    val counter = AtomicInteger()
    return Executors.newFixedThreadPool(threads) { task ->
        Thread(task, "hierograph-signature-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
}

// outputSignature catches its own I/O failures, so a task does not fail.
private fun walkOn(pool: ExecutorService, dirsByModule: List<List<Path>>, signature: (List<Path>) -> Long): List<Long> =
    dirsByModule.map { dirs -> pool.submit(Callable { signature(dirs) }) }.map { it.get() }
