package io.hierograph.hierarchicalgraph.graphdb.mapping.service

import io.hierograph.boltclient.IBoltClient

interface IBoltClientAware {
    fun initialize(boltClient: IBoltClient)
}
