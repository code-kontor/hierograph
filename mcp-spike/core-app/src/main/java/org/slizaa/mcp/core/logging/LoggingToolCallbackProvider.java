package org.slizaa.mcp.core.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.chat.model.ToolContext;

import java.time.Instant;
import java.util.*;

/**
 * Wraps a delegate ToolCallbackProvider, decorating each ToolCallback with logging.
 * This approach is reliable regardless of AOP proxy mechanics — it intercepts at the
 * ToolCallback level, which is exactly where the MCP server invokes tools.
 */
public class LoggingToolCallbackProvider implements ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger("mcp.tool.calls");
    private static final int MAX_RESPONSE_BODY_BYTES = 50 * 1024;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final ToolCallbackProvider delegate;
    private final ObjectMapper objectMapper;

    public LoggingToolCallbackProvider(ToolCallbackProvider delegate) {
        this.delegate = delegate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        ToolCallback[] callbacks = delegate.getToolCallbacks();
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            wrapped[i] = new LoggingToolCallback(callbacks[i]);
        }
        return wrapped;
    }

    private class LoggingToolCallback implements ToolCallback {

        private final ToolCallback delegate;

        LoggingToolCallback(ToolCallback delegate) {
            this.delegate = delegate;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public String call(String toolInput) {
            return logAndCall(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return logAndCall(toolInput, toolContext);
        }

        private String logAndCall(String toolInput, ToolContext toolContext) {
            String toolName = delegate.getToolDefinition().name();

            ToolCallRecord record = new ToolCallRecord();
            record.setTimestamp(Instant.now().toString());
            record.setRequestId(UUID.randomUUID().toString());
            record.setTool(toolName);
            record.setSessionId(Thread.currentThread().getName());

            // Capture request
            Map<String, Object> requestMap = parseJsonSafe(toolInput);
            record.setRequest(requestMap);
            record.setRequestSizeBytes(toolInput != null ? toolInput.getBytes().length : 0);

            long startTime = System.nanoTime();
            String result;

            try {
                result = toolContext != null
                        ? delegate.call(toolInput, toolContext)
                        : delegate.call(toolInput);

                long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                record.setDurationMs(durationMs);

                processSuccessResponse(record, result);
                logRecord(record);

                return result;

            } catch (Throwable ex) {
                long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                record.setDurationMs(durationMs);
                record.setOutcome(ToolCallRecord.Outcome.error);
                record.setErrorType(ex.getClass().getSimpleName());
                record.setErrorMessage(truncate(ex.getMessage(), MAX_ERROR_MESSAGE_LENGTH));

                logRecord(record);

                if (ex instanceof RuntimeException re) throw re;
                throw new RuntimeException(ex);
            }
        }
    }

    private void processSuccessResponse(ToolCallRecord record, String result) {
        int responseSize = result != null ? result.getBytes().length : 0;
        record.setResponseSizeBytes(responseSize);

        // Parse result as JSON to extract metrics
        Map<String, Object> resultMap = parseJsonSafe(result);

        boolean hasError = resultMap != null && resultMap.containsKey("error");
        record.setOutcome(hasError ? ToolCallRecord.Outcome.error : ToolCallRecord.Outcome.success);

        // Extract result metrics
        if (resultMap != null) {
            extractResultMetrics(record, resultMap);
        }

        // Response body (with size cap)
        if (result != null && responseSize <= MAX_RESPONSE_BODY_BYTES) {
            record.setResponseBody(resultMap != null ? resultMap : result);
            record.setResponseTruncated(false);
        } else if (result != null) {
            record.setResponseBody("[TRUNCATED — " + responseSize + " bytes, cap is " + MAX_RESPONSE_BODY_BYTES + "]");
            record.setResponseTruncated(true);
        }
    }

    @SuppressWarnings("unchecked")
    private void extractResultMetrics(ToolCallRecord record, Map<String, Object> resultMap) {
        Map<String, Object> summary = new LinkedHashMap<>();

        if (resultMap.containsKey("summary") && resultMap.get("summary") instanceof Map) {
            summary.putAll((Map<String, Object>) resultMap.get("summary"));
        }
        if (resultMap.containsKey("exists")) {
            summary.put("exists", resultMap.get("exists"));
        }
        if (resultMap.containsKey("weight")) {
            summary.put("weight", resultMap.get("weight"));
        }
        if (resultMap.containsKey("edges") && resultMap.get("edges") instanceof List<?> edges) {
            record.setResultCount(edges.size());
        }
        if (resultMap.containsKey("methods") && resultMap.get("methods") instanceof List<?> methods) {
            record.setResultCount(methods.size());
        }
        if (resultMap.containsKey("fields") && resultMap.get("fields") instanceof List<?> fields) {
            record.setResultCount(fields.size());
        }
        if (resultMap.containsKey("results") && resultMap.get("results") instanceof List<?> results) {
            record.setResultCount(results.size());
        }
        if (resultMap.containsKey("error")) {
            summary.put("error", resultMap.get("error"));
        }

        if (!summary.isEmpty()) {
            record.setResultSummary(summary);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonSafe(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void logRecord(ToolCallRecord record) {
        try {
            String json = objectMapper.writeValueAsString(record);
            log.info(json);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize tool call record for tool={}: {}",
                    record.getTool(), e.getMessage());
        }
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return null;
        return s.length() <= maxLength ? s : s.substring(0, maxLength) + "...";
    }
}
