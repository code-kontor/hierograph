package io.hierograph.hierarchicalgraph.graphdb.mapping.service

import org.slizaa.core.boltclient.IBoltClient

interface IBoltClientAware {
    fun initialize(boltClient: IBoltClient)
}
