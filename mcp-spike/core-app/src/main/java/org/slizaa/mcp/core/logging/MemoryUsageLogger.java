package org.slizaa.mcp.core.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "slizaa.memory-usage-logger.enabled", havingValue = "true", matchIfMissing = false)
public class MemoryUsageLogger {

    private static final Logger log = LoggerFactory.getLogger(MemoryUsageLogger.class);

    @Scheduled(fixedRate = 30_000)
    public void logMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long totalMb = runtime.totalMemory() / (1024 * 1024);
        long freeMb = runtime.freeMemory() / (1024 * 1024);
        long usedMb = totalMb - freeMb;
        long maxMb = runtime.maxMemory() / (1024 * 1024);
        log.info("Memory usage: used={}MB, free={}MB, total={}MB, max={}MB", usedMb, freeMb, totalMb, maxMb);
    }
}
