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
package io.hierograph.mcp.server.core.pagination

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A pagination cursor.
 *
 * A cursor is a self-contained, stateless token that encodes everything needed to resume a paginated
 * query — it carries no server-side state, so it survives server restarts as long as the underlying
 * data is unchanged. On the wire a cursor is a base64-URL-encoded JSON object; this class is the
 * decoded form.
 *
 * The cursor is opaque to the LLM: it is passed through verbatim and never constructed or parsed by
 * the caller. The fields exist for the server's own validation and resumption logic.
 *
 * @property version cursor format version (currently [CURRENT_VERSION]). Lets the format evolve;
 *   cursors carrying a version the server no longer supports fail with a clear error.
 * @property tool the name of the tool that issued this cursor. Lets the server detect a cursor being
 *   used on a different tool than it was issued for.
 * @property queryHash a truncated SHA-256 hash (first 12 bytes, base64-encoded) of the request's
 *   query parameters. Lets the server detect when the caller changes parameters between pages.
 * @property dataHash an identifier for the snapshot of the underlying graph data when this cursor was
 *   issued. Lets the server detect when the underlying data has changed, invalidating the offset.
 * @property offset the zero-indexed position in the result list to resume from.
 */
data class Cursor @JsonCreator constructor(
    @JsonProperty("v") val version: Int,
    @JsonProperty("tool") val tool: String,
    @JsonProperty("qh") val queryHash: String,
    @JsonProperty("dh") val dataHash: String,
    @JsonProperty("offset") val offset: Int
) {
    companion object {
        /** The cursor format version this server issues. */
        const val CURRENT_VERSION: Int = 1
    }
}
