package org.slizaa.mcp.core.logging

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["slizaa.memory-usage-logger.enabled"], havingValue = "true", matchIfMissing = false)
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
