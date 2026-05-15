package org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.dsl;

import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.DefaultLabelDefinition;

import java.util.Objects;

@FunctionalInterface
public interface ILabelDefinitionProcessor {

  /**
   * <p>
   * </p>
   *
   * @param hgNode
   * @param labelDefinition
   */
  void processLabelDefinition(HGNode hgNode, DefaultLabelDefinition labelDefinition);

  default ILabelDefinitionProcessor and(ILabelDefinitionProcessor next) {
    Objects.requireNonNull(next);
    return (hgNode, labelDefinition) -> {
      processLabelDefinition(hgNode, labelDefinition);
      next.processLabelDefinition(hgNode, labelDefinition);
    };
  }
}
