package org.slizaa.hierarchicalgraph.graphdb.mapping.service;

import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.core.progressmonitor.IProgressMonitor;
import org.slizaa.hierarchicalgraph.core.model.HGRootNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.service.internal.DefaultMappingService;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IMappingProvider;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
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
  HGRootNode convert(IMappingProvider mappingProvider, IBoltClient boltClient, IProgressMonitor progressMonitor)
      throws MappingException;

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public static IMappingService createHierarchicalgraphMappingService() {
    return new DefaultMappingService();
  }
}
