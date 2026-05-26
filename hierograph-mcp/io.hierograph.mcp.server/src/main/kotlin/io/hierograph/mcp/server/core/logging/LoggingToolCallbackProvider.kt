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

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.ToolCallbackProvider
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.chat.model.ToolContext
import java.time.Instant
import java.util.*

/**
 * Wraps a delegate ToolCallbackProvider, decorating each ToolCallback with logging.
 * This approach is reliable regardless of AOP proxy mechanics -- it intercepts at the
 * ToolCallback level, which is exactly where the MCP server invokes tools.
 */
class LoggingToolCallbackProvider(
    private val delegate: ToolCallbackProvider
) : ToolCallbackProvider {

    companion object {
        private val log = LoggerFactory.getLogger("tools.tool.calls")
        private const val MAX_RESPONSE_BODY_BYTES = 50 * 1024
        private const val MAX_ERROR_MESSAGE_LENGTH = 500
    }

    private val objectMapper = ObjectMapper().apply {
        disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
    }

    override fun getToolCallbacks(): Array<ToolCallback> {
        val callbacks = delegate.toolCallbacks
        return Array(callbacks.size) { i -> LoggingToolCallback(callbacks[i]) }
    }

    private inner class LoggingToolCallback(
        private val delegate: ToolCallback
    ) : ToolCallback {

        override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

        override fun call(toolInput: String): String = logAndCall(toolInput, null)

        override fun call(toolInput: String, toolContext: ToolContext): String =
            logAndCall(toolInput, toolContext)

        private fun logAndCall(toolInput: String, toolContext: ToolContext?): String {
            val toolName = delegate.toolDefinition.name()

            val record = ToolCallRecord().apply {
                timestamp = Instant.now().toString()
                requestId = UUID.randomUUID().toString()
                tool = toolName
                sessionId = Thread.currentThread().name
                request = parseJsonSafe(toolInput)
                requestSizeBytes = toolInput.toByteArray().size
            }

            val startTime = System.nanoTime()

            return try {
                val result = if (toolContext != null) {
                    delegate.call(toolInput, toolContext)
                } else {
                    delegate.call(toolInput)
                }

                val durationMs = (System.nanoTime() - startTime) / 1_000_000
                record.durationMs = durationMs

                processSuccessResponse(record, result)
                logRecord(record)

                result
            } catch (ex: Throwable) {
                val durationMs = (System.nanoTime() - startTime) / 1_000_000
                record.durationMs = durationMs
                record.outcome = ToolCallRecord.Outcome.error
                record.errorType = ex.javaClass.simpleName
                record.errorMessage = truncate(ex.message, MAX_ERROR_MESSAGE_LENGTH)

                logRecord(record)

                if (ex is RuntimeException) throw ex
                throw RuntimeException(ex)
            }
        }
    }

    private fun processSuccessResponse(record: ToolCallRecord, result: String?) {
        val responseSize = result?.toByteArray()?.size ?: 0
        record.responseSizeBytes = responseSize

        val resultMap = parseJsonSafe(result)

        val hasError = resultMap?.containsKey("error") == true
        record.outcome = if (hasError) ToolCallRecord.Outcome.error else ToolCallRecord.Outcome.success

        if (resultMap != null) {
            extractResultMetrics(record, resultMap)
        }

        if (result != null && responseSize <= MAX_RESPONSE_BODY_BYTES) {
            record.responseBody = resultMap ?: result
            record.responseTruncated = false
        } else if (result != null) {
            record.responseBody = "[TRUNCATED -- $responseSize bytes, cap is $MAX_RESPONSE_BODY_BYTES]"
            record.responseTruncated = true
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractResultMetrics(record: ToolCallRecord, resultMap: Map<String, Any>) {
        val summary = LinkedHashMap<String, Any>()

        val summaryVal = resultMap["summary"]
        if (summaryVal is Map<*, *>) {
            summary.putAll(summaryVal as Map<String, Any>)
        }
        resultMap["exists"]?.let { summary["exists"] = it }
        resultMap["weight"]?.let { summary["weight"] = it }

        (resultMap["edges"] as? List<*>)?.let { record.resultCount = it.size }
        (resultMap["methods"] as? List<*>)?.let { record.resultCount = it.size }
        (resultMap["fields"] as? List<*>)?.let { record.resultCount = it.size }
        (resultMap["results"] as? List<*>)?.let { record.resultCount = it.size }

        resultMap["error"]?.let { summary["error"] = it }

        if (summary.isNotEmpty()) {
            record.resultSummary = summary
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseJsonSafe(json: String?): Map<String, Any>? {
        if (json.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(json, Map::class.java) as Map<String, Any>
        } catch (e: JsonProcessingException) {
            null
        }
    }

    private fun logRecord(record: ToolCallRecord) {
        try {
            val json = objectMapper.writeValueAsString(record)
            log.info(json)
        } catch (e: JsonProcessingException) {
            log.warn("Failed to serialize tool call record for tool={}: {}", record.tool, e.message)
        }
    }

    private fun truncate(s: String?, maxLength: Int): String? {
        if (s == null) return null
        return if (s.length <= maxLength) s else s.substring(0, maxLength) + "..."
    }
}
