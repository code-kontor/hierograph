package org.slizaa.mcp.core.logging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolCallRecord {

    public enum Outcome { success, error, truncated }

    // --- Identity and timing ---

    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("tool")
    private String tool;

    @JsonProperty("duration_ms")
    private long durationMs;

    // --- Request ---

    @JsonProperty("request")
    private Map<String, Object> request;

    @JsonProperty("request_size_bytes")
    private int requestSizeBytes;

    // --- Response ---

    @JsonProperty("response_size_bytes")
    private int responseSizeBytes;

    @JsonProperty("result_count")
    private Integer resultCount;

    @JsonProperty("result_summary")
    private Map<String, Object> resultSummary;

    // --- Response body ---

    @JsonProperty("response_body")
    private Object responseBody;

    @JsonProperty("response_truncated")
    private Boolean responseTruncated;

    // --- Outcome ---

    @JsonProperty("outcome")
    private Outcome outcome;

    @JsonProperty("error_type")
    private String errorType;

    @JsonProperty("error_message")
    private String errorMessage;

    // --- Internal context ---

    @JsonProperty("internal")
    private Map<String, Object> internal;

    // --- Getters and setters ---

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getTool() { return tool; }
    public void setTool(String tool) { this.tool = tool; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public Map<String, Object> getRequest() { return request; }
    public void setRequest(Map<String, Object> request) { this.request = request; }

    public int getRequestSizeBytes() { return requestSizeBytes; }
    public void setRequestSizeBytes(int requestSizeBytes) { this.requestSizeBytes = requestSizeBytes; }

    public int getResponseSizeBytes() { return responseSizeBytes; }
    public void setResponseSizeBytes(int responseSizeBytes) { this.responseSizeBytes = responseSizeBytes; }

    public Integer getResultCount() { return resultCount; }
    public void setResultCount(Integer resultCount) { this.resultCount = resultCount; }

    public Map<String, Object> getResultSummary() { return resultSummary; }
    public void setResultSummary(Map<String, Object> resultSummary) { this.resultSummary = resultSummary; }

    public Object getResponseBody() { return responseBody; }
    public void setResponseBody(Object responseBody) { this.responseBody = responseBody; }

    public Boolean getResponseTruncated() { return responseTruncated; }
    public void setResponseTruncated(Boolean responseTruncated) { this.responseTruncated = responseTruncated; }

    public Outcome getOutcome() { return outcome; }
    public void setOutcome(Outcome outcome) { this.outcome = outcome; }

    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Map<String, Object> getInternal() { return internal; }
    public void setInternal(Map<String, Object> internal) { this.internal = internal; }
}
