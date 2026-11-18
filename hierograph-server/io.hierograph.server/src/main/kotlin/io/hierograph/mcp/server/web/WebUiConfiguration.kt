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
package io.hierograph.mcp.server.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.resource.PathResourceResolver
import java.util.concurrent.TimeUnit

/**
 * Serves the web UI at `/ui/`, next to `/graphql` on the same origin.
 *
 * The UI is a single-page app built by `hierograph-web` into the jar under `META-INF/resources/ui/`
 * (the location can be changed with `hierograph.web.ui-location`, which tests use).
 *
 * - `/ui/assets/` holds the hashed build output; it never changes under the same name, so it is cached
 *   for a year.
 * - Everything else under `/ui/` is served `no-cache`, so a new server version is picked up at once. A
 *   path without a file extension is a route of the app, not a file: it gets `index.html`, so a deep
 *   link survives a reload. A missing file with an extension is a 404.
 * - When the UI is not part of the build (`-Dhierograph.web.skip`), `/ui/` answers with a short page
 *   that says so, instead of a 404 that leaves the user guessing.
 * - `/` and `/ui` redirect to `/ui/`.
 */
@Configuration
class WebUiConfiguration(
    @Value("\${hierograph.web.ui-location:classpath:/META-INF/resources/ui/}") uiLocation: String,
) : WebMvcConfigurer {

    private val location = if (uiLocation.endsWith("/")) uiLocation else "$uiLocation/"

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("$UI_PATH/assets/**")
            .addResourceLocations("${location}assets/")
            .setCacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable())
            .resourceChain(true)
        registry.addResourceHandler("$UI_PATH/**")
            .addResourceLocations(location)
            .setCacheControl(CacheControl.noCache())
            .resourceChain(true)
            .addResolver(AppRouteResolver(ClassPathResource(NOT_BUILT_PAGE)))
    }

    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addRedirectViewController("/", "$UI_PATH/")
        registry.addRedirectViewController(UI_PATH, "$UI_PATH/")
        // The resource handler rejects an empty resource path before any resolver runs, so the UI's
        // root goes to index.html explicitly.
        registry.addViewController("$UI_PATH/").setViewName("forward:$UI_PATH/$INDEX")
    }

    /**
     * Resolves a file of the UI, and answers an app route (a path without a file extension) with
     * `index.html`, or with [notBuilt] when the UI is not part of the build.
     */
    internal class AppRouteResolver(private val notBuilt: Resource) : PathResourceResolver() {

        override fun getResource(resourcePath: String, location: Resource): Resource? {
            super.getResource(resourcePath, location)?.let { return it }
            if (resourcePath == INDEX) return notBuilt
            if ('.' in resourcePath.substringAfterLast('/')) return null
            val index = location.createRelative(INDEX)
            return if (index.isReadable) index else notBuilt
        }
    }

    companion object {
        const val UI_PATH = "/ui"
        private const val INDEX = "index.html"
        private const val NOT_BUILT_PAGE = "io/hierograph/mcp/server/web/ui-not-built.html"
    }
}
