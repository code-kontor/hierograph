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
package io.hierograph.server.graphql.controller

import io.hierograph.server.graphservice.views.DefaultViewInfo
import io.hierograph.server.graphservice.views.ViewInfo
import io.hierograph.server.graphservice.views.ViewService
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * `views` and `defaultView`: the views that can be passed as `view` and, beside them, the hierarchy as scanned,
 * as the MCP tool `list_views` lists them.
 */
@Controller
class ViewInfoController(private val viewService: ViewService? = null) {

    @QueryMapping
    fun views(): List<ViewInfo> = viewService?.infos() ?: emptyList()

    @QueryMapping
    fun defaultView(): DefaultViewInfo = viewService?.defaultInfo() ?: DefaultViewInfo(null)
}
