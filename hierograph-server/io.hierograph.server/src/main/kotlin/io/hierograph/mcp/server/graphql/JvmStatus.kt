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
package io.hierograph.mcp.server.graphql

import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.lang.management.MemoryUsage

/**
 * `JvmStatus`: the server JVM's current metrics, read from the platform MXBeans on each request. Cheap: no
 * walk, no history, nothing kept between requests. Byte and millisecond values are `Long`s, served as GraphQL
 * `Float` (exact up to 2^53).
 */
class JvmStatus private constructor(
    val javaVersion: String,
    val javaVendor: String,
    val vmName: String,
    val uptimeMs: Long,
    val availableProcessors: Int,
    val heap: Memory,
    val nonHeap: Memory,
    val garbageCollectors: List<GarbageCollector>,
    val threads: Threads,
    /** The process's recent CPU load, 0 to 1; `null` when the JVM doesn't report it. */
    val processCpuLoad: Double?,
) {

    /**
     * A memory area's usage. [maxBytes] is `null` when the JVM sets no limit. [usedAfterGcBytes] is what the
     * last collection left in use, the memory really kept; `null` for non-heap and before the first collection.
     */
    class Memory(val usedBytes: Long, val committedBytes: Long, val maxBytes: Long?, val usedAfterGcBytes: Long?) {
        companion object {
            fun of(usage: MemoryUsage, usedAfterGc: Long? = null) =
                Memory(usage.used, usage.committed, usage.max.takeIf { it >= 0 }, usedAfterGc)
        }
    }

    /** A garbage collector's collections and their accumulated time since the JVM started. */
    class GarbageCollector(val name: String, val count: Long, val timeMs: Long)

    class Threads(val live: Int, val peak: Int, val daemon: Int)

    companion object {
        fun current(): JvmStatus {
            val runtime = ManagementFactory.getRuntimeMXBean()
            val memory = ManagementFactory.getMemoryMXBean()
            val threads = ManagementFactory.getThreadMXBean()
            val collectors = ManagementFactory.getGarbageCollectorMXBeans().map {
                GarbageCollector(it.name, it.collectionCount.coerceAtLeast(0), it.collectionTime.coerceAtLeast(0))
            }
            return JvmStatus(
                javaVersion = System.getProperty("java.version"),
                javaVendor = System.getProperty("java.vendor"),
                vmName = runtime.vmName,
                uptimeMs = runtime.uptime,
                availableProcessors = Runtime.getRuntime().availableProcessors(),
                heap = Memory.of(memory.heapMemoryUsage, heapUsedAfterGc(collectors)),
                nonHeap = Memory.of(memory.nonHeapMemoryUsage),
                garbageCollectors = collectors,
                threads = Threads(threads.threadCount, threads.peakThreadCount, threads.daemonThreadCount),
                processCpuLoad = processCpuLoad(),
            )
        }

        // The sum over the heap pools of what their last collection left; before the first collection the
        // pools report 0, which would read as an empty heap.
        private fun heapUsedAfterGc(collectors: List<GarbageCollector>): Long? {
            if (collectors.sumOf { it.count } == 0L) return null
            return ManagementFactory.getMemoryPoolMXBeans()
                .filter { it.type == MemoryType.HEAP }
                .sumOf { it.collectionUsage?.used ?: 0L }
        }

        // The `com.sun.management` extension reports it; a negative value means "not available".
        private fun processCpuLoad(): Double? =
            (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
                ?.processCpuLoad
                ?.takeIf { it >= 0 }
    }
}
