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

/**
 * Thrown by [CursorCodec.decode] when a cursor string cannot be turned back into a [Cursor] —
 * it is not valid base64, the decoded content is not valid JSON, a required field is missing or
 * has the wrong type, or a field carries a structurally invalid value (e.g. a negative offset).
 *
 * This corresponds to the `INVALID_CURSOR_FORMAT` error case: the cursor is corrupted or malformed,
 * and the recovery path is to restart pagination by calling the tool without a cursor parameter.
 *
 * Note that an *unsupported version* is deliberately not a format error — a cursor with a known but
 * no-longer-supported version still decodes successfully, so that the higher-level validation layer
 * can report the precise `STALE_CURSOR_VERSION` error with the offending version number.
 */
class CursorFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
