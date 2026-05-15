package org.slizaa.mcp.core.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.*;

@Aspect
@Component
public class ToolCallLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger("mcp.tool.calls");
    private static final int MAX_RESPONSE_BODY_BYTES = 50 * 1024; // 50KB cap
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final ObjectMapper objectMapper;

    public ToolCallLoggingAspect() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object logToolCall(ProceedingJoinPoint joinPoint) throws Throwable {

        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Tool toolAnnotation = method.getAnnotation(Tool.class);

        ToolCallRecord record = new ToolCallRecord();
        record.setTimestamp(Instant.now().toString());
        record.setRequestId(UUID.randomUUID().toString());
        record.setTool(toolAnnotation.name());
        record.setSessionId(resolveSessionId());

        // Capture request arguments
        Map<String, Object> requestArgs = captureRequestArgs(method, joinPoint.getArgs());
        record.setRequest(requestArgs);
        record.setRequestSizeBytes(serializeSize(requestArgs));

        long startTime = System.nanoTime();
        Object result;

        try {
            result = joinPoint.proceed();
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            record.setDurationMs(durationMs);

            // Process response
            processSuccessResponse(record, result);

            // Log the record
            logRecord(record);

            return result;

        } catch (Throwable ex) {
            long durationMs = (System.nanoTime() - startTime) / 1_000_000;
            record.setDurationMs(durationMs);
            record.setOutcome(ToolCallRecord.Outcome.error);
            record.setErrorType(ex.getClass().getSimpleName());
            record.setErrorMessage(truncate(ex.getMessage(), MAX_ERROR_MESSAGE_LENGTH));

            logRecord(record);

            throw ex;
        }
    }

    private void processSuccessResponse(ToolCallRecord record, Object result) {
        // Determine outcome
        boolean hasError = false;
        boolean isTruncated = false;

        if (result instanceof Map<?, ?> map) {
            hasError = map.containsKey("error");
            // Check for truncation indicators
            if (map.containsKey("max_length_searched") || map.containsKey("max_depth_requested")) {
                isTruncated = true;
            }
        }

        record.setOutcome(hasError ? ToolCallRecord.Outcome.error
                : isTruncated ? ToolCallRecord.Outcome.truncated
                : ToolCallRecord.Outcome.success);

        // Response size
        String serialized = safeSerialize(result);
        int responseSize = serialized != null ? serialized.getBytes().length : 0;
        record.setResponseSizeBytes(responseSize);

        // Result count and summary
        extractResultMetrics(record, result);

        // Response body with cap
        if (serialized != null && responseSize <= MAX_RESPONSE_BODY_BYTES) {
            record.setResponseBody(result);
            record.setResponseTruncated(false);
        } else if (serialized != null) {
            record.setResponseBody("[TRUNCATED — " + responseSize + " bytes, cap is " + MAX_RESPONSE_BODY_BYTES + "]");
            record.setResponseTruncated(true);
        }
    }

    @SuppressWarnings("unchecked")
    private void extractResultMetrics(ToolCallRecord record, Object result) {
        Map<String, Object> summary = new LinkedHashMap<>();

        if (result instanceof List<?> list) {
            record.setResultCount(list.size());
            if (!list.isEmpty() && list.getFirst() instanceof Map) {
                // Extract names/ids from list results (e.g., find_node)
                List<String> names = new ArrayList<>();
                for (Object item : list.subList(0, Math.min(5, list.size()))) {
                    if (item instanceof Map<?, ?> m && m.containsKey("name")) {
                        names.add(String.valueOf(m.get("name")));
                    }
                }
                if (!names.isEmpty()) {
                    summary.put("first_names", names);
                }
            }
        } else if (result instanceof Map<?, ?> map) {
            // Extract tool-specific summaries
            if (map.containsKey("summary") && map.get("summary") instanceof Map) {
                summary.putAll((Map<String, Object>) map.get("summary"));
            }
            if (map.containsKey("exists")) {
                summary.put("exists", map.get("exists"));
            }
            if (map.containsKey("length")) {
                summary.put("length", map.get("length"));
            }
            if (map.containsKey("weight")) {
                summary.put("weight", map.get("weight"));
            }
            if (map.containsKey("edges") && map.get("edges") instanceof List<?> edges) {
                record.setResultCount(edges.size());
            }
            if (map.containsKey("results") && map.get("results") instanceof List<?> results) {
                record.setResultCount(results.size());
            }
            if (map.containsKey("path") && map.get("path") instanceof List<?> path) {
                record.setResultCount(path.size());
            }
            if (map.containsKey("error")) {
                summary.put("error", map.get("error"));
            }
        }

        if (!summary.isEmpty()) {
            record.setResultSummary(summary);
        }
    }

    private Map<String, Object> captureRequestArgs(Method method, Object[] args) {
        Map<String, Object> requestArgs = new LinkedHashMap<>();
        Parameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            String name;
            ToolParam toolParam = params[i].getAnnotation(ToolParam.class);
            name = params[i].getName();
            if (args[i] != null) {
                requestArgs.put(name, args[i]);
            }
        }
        return requestArgs;
    }

    private String resolveSessionId() {
        // In Streamable HTTP mode, the session is per-connection.
        // We use the thread name as a proxy since each MCP session gets its own thread.
        return Thread.currentThread().getName();
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

    private int serializeSize(Object obj) {
        String s = safeSerialize(obj);
        return s != null ? s.getBytes().length : 0;
    }

    private String safeSerialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return null;
        return s.length() <= maxLength ? s : s.substring(0, maxLength) + "...";
    }
}
