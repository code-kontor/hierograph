package org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.dsl;

import org.slizaa.hierarchicalgraph.core.model.HGNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class ExclusiveChoiceBuilder {

  /**
   * -
   */
  private List<ExclusiveChoiceAlternative> _alternatives = new ArrayList<>();

  /**
   * <p>
   * </p>
   *
   * @param condition
   * @return
   */
  public ExclusiveChoiceAlternative when(Function<HGNode, Boolean> condition) {

    ExclusiveChoiceAlternative builder = new ExclusiveChoiceAlternative(this, condition);
    this._alternatives.add(builder);
    return builder;
  }

  /**
   * <p>
   * </p>
   *
   * @param processor
   * @return
   */
  public ILabelDefinitionProcessor otherwise(ILabelDefinitionProcessor processor) {

    //
    return (n, l) -> {

      //
      for (ExclusiveChoiceAlternative alternative : this._alternatives) {
        if (alternative.getCondition().apply(n)) {
          alternative.getProcessor().processLabelDefinition(n, l);
          return;
        }
      }

      //
      processor.processLabelDefinition(n, l);
    };
  }
}
