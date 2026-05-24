package org.slizaa.mcp.core.logging

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
class ToolCallRecord {

    enum class Outcome { success, error, truncated }

    // --- Identity and timing ---

    @JsonProperty("timestamp")
    var timestamp: String? = null

    @JsonProperty("session_id")
    var sessionId: String? = null

    @JsonProperty("request_id")
    var requestId: String? = null

    @JsonProperty("tool")
    var tool: String? = null

    @JsonProperty("duration_ms")
    var durationMs: Long = 0

    // --- Request ---

    @JsonProperty("request")
    var request: Map<String, Any>? = null

    @JsonProperty("request_size_bytes")
    var requestSizeBytes: Int = 0

    // --- Response ---

    @JsonProperty("response_size_bytes")
    var responseSizeBytes: Int = 0

    @JsonProperty("result_count")
    var resultCount: Int? = null

    @JsonProperty("result_summary")
    var resultSummary: Map<String, Any>? = null

    // --- Response body ---

    @JsonProperty("response_body")
    var responseBody: Any? = null

    @JsonProperty("response_truncated")
    var responseTruncated: Boolean? = null

    // --- Outcome ---

    @JsonProperty("outcome")
    var outcome: Outcome? = null

    @JsonProperty("error_type")
    var errorType: String? = null

    @JsonProperty("error_message")
    var errorMessage: String? = null

    // --- Internal context ---

    @JsonProperty("internal")
    var internal: Map<String, Any>? = null
}
