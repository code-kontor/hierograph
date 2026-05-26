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
package io.hierograph.boltclient

import io.hierograph.boltclient.internal.BoltClientFactoryImpl
import java.util.concurrent.ExecutorService

interface IBoltClientFactory {
    fun createBoltClient(uri: String, name: String? = null, description: String? = null): IBoltClient

    companion object {
        fun newInstance(executorService: ExecutorService): IBoltClientFactory {
            return BoltClientFactoryImpl(executorService)
        }
    }
}
