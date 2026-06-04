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
package io.hierograph.mcp.server.tools.detail

import io.hierograph.mcp.server.core.pagination.PaginationSpec

interface IDetailDependencies {

    /**
     * @param spec the pagination policy and tool identity to stamp into issued cursors — the *outer*
     *   tool the caller is serving (e.g. `outgoing_dependencies`), so a detail-level cursor is bound
     *   to that tool. The incoming direction passes its own spec and swaps [fromId]/[toId].
     * @param cursor opaque pagination cursor from a previous response, or null to start from the first page.
     */
    fun detailDependencies(
        fromId: Long,
        toId: Long,
        relationship: String?,
        limit: Int?,
        cursor: String?,
        spec: PaginationSpec
    ): Map<String, Any?>
}
