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
