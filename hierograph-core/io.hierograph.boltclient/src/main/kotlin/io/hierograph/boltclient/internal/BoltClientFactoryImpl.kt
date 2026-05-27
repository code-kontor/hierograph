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
package io.hierograph.boltclient.internal

import io.hierograph.boltclient.IBoltClient
import io.hierograph.boltclient.IBoltClientFactory
import java.util.concurrent.ExecutorService

class BoltClientFactoryImpl(
    private val executorService: ExecutorService
) : IBoltClientFactory {

    override fun createBoltClient(uri: String, name: String?, description: String?): IBoltClient {
        return BoltClientImpl(executorService, uri, name, description)
    }
}
