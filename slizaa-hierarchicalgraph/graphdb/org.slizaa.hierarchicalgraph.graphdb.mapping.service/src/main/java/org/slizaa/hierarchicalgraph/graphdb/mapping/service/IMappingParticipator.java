/**
 *
 */
package org.slizaa.hierarchicalgraph.graphdb.mapping.service;

import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.hierarchicalgraph.core.model.HGRootNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IMappingProvider;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 *
 */
public interface IMappingParticipator {

  void postCreate(HGRootNode rootNode, IMappingProvider mappingDescriptor, IBoltClient boltClient);
}
