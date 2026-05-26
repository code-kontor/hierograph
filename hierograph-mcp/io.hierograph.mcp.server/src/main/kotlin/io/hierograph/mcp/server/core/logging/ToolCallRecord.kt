/*
 * Copyright 2024 Gerd Wuetherich
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
