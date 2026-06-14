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
package io.hierograph.itest.support

import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * Starts [HierographImageContainer] once, before the first test of the whole run, and stops it
 * once, after the last test.
 *
 * The started container is registered as a [ExtensionContext.Store.CloseableResource] in the
 * *root* store, so JUnit closes it exactly once when the entire test plan finishes — independent
 * of how many test classes use it.
 */
class HierographImageExtension : BeforeAllCallback {

    override fun beforeAll(context: ExtensionContext) {
        context.root
            .getStore(ExtensionContext.Namespace.GLOBAL)
            .getOrComputeIfAbsent(KEY) { StartedImage() }
    }

    private class StartedImage : ExtensionContext.Store.CloseableResource {
        init {
            HierographImageContainer.instance.start()
        }

        override fun close() {
            HierographImageContainer.instance.stop()
        }
    }

    private companion object {
        const val KEY = "hierograph-itest-image"
    }
}
