package org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.dsl;

import org.slizaa.hierarchicalgraph.core.model.HGNode;

import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class LabelMappingConditionBuilder {

  /**
   * -
   */
  private Function<HGNode, Boolean> _condition;

  /**
   * <p>
   * Creates a new instance of type {@link LabelMappingConditionBuilder}.
   * </p>
   *
   * @param condition
   */
  public LabelMappingConditionBuilder(Function<HGNode, Boolean> condition) {
    this._condition = checkNotNull(condition);
  }

  /**
   * <p>
   * </p>
   *
   * @param processor
   * @return
   */
  public ILabelDefinitionProcessor then(ILabelDefinitionProcessor processor) {

    //
    checkNotNull(processor);

    //
    return (hgNode, labelDefinition) -> {
      if (this._condition.apply(hgNode)) {
        processor.processLabelDefinition(hgNode, labelDefinition);
      }
    };
  }
}
