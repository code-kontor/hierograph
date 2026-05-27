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
package io.hierograph.mcp.server.core.logging

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["hierograph.memory-usage-logger.enabled"], havingValue = "true", matchIfMissing = false)
class MemoryUsageLogger {

    companion object {
        private val log = LoggerFactory.getLogger(MemoryUsageLogger::class.java)
    }

    @Scheduled(fixedRate = 30_000)
    fun logMemoryUsage() {
        val runtime = Runtime.getRuntime()
        runtime.gc() // Trigger garbage collection to get more accurate memory usage
        val totalMb = runtime.totalMemory() / (1024 * 1024)
        val freeMb = runtime.freeMemory() / (1024 * 1024)
        val usedMb = totalMb - freeMb
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        log.info("Memory usage: used={}MB, free={}MB, total={}MB, max={}MB", usedMb, freeMb, totalMb, maxMb)
    }
}
