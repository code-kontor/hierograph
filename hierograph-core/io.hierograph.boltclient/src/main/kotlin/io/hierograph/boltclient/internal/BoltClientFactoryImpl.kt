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
