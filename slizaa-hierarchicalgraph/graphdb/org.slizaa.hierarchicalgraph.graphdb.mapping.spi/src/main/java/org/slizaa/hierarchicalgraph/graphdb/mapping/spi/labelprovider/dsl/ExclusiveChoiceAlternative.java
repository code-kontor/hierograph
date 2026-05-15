package org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.dsl;

import org.slizaa.hierarchicalgraph.core.model.HGNode;

import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 *
 */
public class ExclusiveChoiceAlternative {

  /**
   * -
   */
  private ExclusiveChoiceBuilder _parent;

  /**
   * -
   */
  private Function<HGNode, Boolean> _condition;

  /**
   * -
   */
  private ILabelDefinitionProcessor _processor;

  /**
   * <p>
   * Creates a new instance of type {@link ExclusiveChoiceAlternative}.
   * </p>
   *
   * @param parent
   * @param condition
   */
  public ExclusiveChoiceAlternative(ExclusiveChoiceBuilder parent, Function<HGNode, Boolean> condition) {

    //
    this._parent = checkNotNull(parent);
    this._condition = checkNotNull(condition);
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public Function<HGNode, Boolean> getCondition() {
    return this._condition;
  }

  public ILabelDefinitionProcessor getProcessor() {
    return this._processor;
  }

  /**
   * <p>
   * </p>
   *
   * @param processor
   * @return
   */
  public ExclusiveChoiceBuilder then(ILabelDefinitionProcessor processor) {
    this._processor = checkNotNull(processor);
    return this._parent;
  }
}
