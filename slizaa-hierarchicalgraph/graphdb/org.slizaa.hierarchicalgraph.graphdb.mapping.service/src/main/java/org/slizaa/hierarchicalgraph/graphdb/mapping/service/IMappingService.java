package org.slizaa.hierarchicalgraph.graphdb.mapping.service;

import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.hierarchicalgraph.core.model.HGRootNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.service.internal.DefaultMappingService;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IMappingProvider;

public interface IMappingService {

  /**
   * <p>
   * </p>
   *
   * @param mappingProvider
   * @param boltClient
   *
   * @return
   * @throws MappingException
   */
  HGRootNode convert(IMappingProvider mappingProvider, IBoltClient boltClient)
      throws MappingException;

    public static IMappingService createHierarchicalgraphMappingService() {
    return new DefaultMappingService();
  }
}
