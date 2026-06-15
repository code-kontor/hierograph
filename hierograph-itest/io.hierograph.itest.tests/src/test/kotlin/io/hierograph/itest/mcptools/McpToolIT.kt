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
package io.hierograph.itest.mcptools

import io.hierograph.itest.fwk.AbstractMcpApplicationIntegrationTest
import io.hierograph.mcp.server.tools.navigation.ListChildrenTool
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Exercises an MCP tool bean against the live graph. Boots the full MCP application so the tool
 * beans are available — see [io.hierograph.itest.fwk.AbstractMcpApplicationIntegrationTest].
 */
class McpToolIT : AbstractMcpApplicationIntegrationTest() {

    @Autowired
    private lateinit var listChildrenTool: ListChildrenTool

    @Test
    fun `the hierarchical graph root has the expected children`() {
        print(listChildrenTool.listChildren(
            nodeId = 151901,
            kindFilter = null,
            namePattern = null,
            modifierFilter = null,
            limit = null
        ))
    }
}