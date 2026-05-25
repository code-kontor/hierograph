package io.hierograph.hierarchicalgraph.graphdb.mapping.service

import io.hierograph.hierarchicalgraph.core.model.HGRootNode
import io.hierograph.hierarchicalgraph.graphdb.mapping.spi.IMappingProvider
import org.slizaa.core.boltclient.IBoltClient

interface IMappingService {
    fun convert(mappingProvider: IMappingProvider, boltClient: IBoltClient): HGRootNode
}
