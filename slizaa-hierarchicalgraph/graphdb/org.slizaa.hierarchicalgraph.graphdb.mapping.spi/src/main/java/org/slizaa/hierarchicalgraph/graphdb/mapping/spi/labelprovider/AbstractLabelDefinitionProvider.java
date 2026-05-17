package org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider;

import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ILabelDefinitionProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.dsl.ILabelAndPropertyProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.dsl.ILabelDefinitionProcessor;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.dsl.LabelMappingDsl;

import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;

public abstract class AbstractLabelDefinitionProvider extends LabelMappingDsl implements ILabelDefinitionProvider {

  private ILabelDefinitionProcessor _processor;

  /**
   * Creates a new instance of {@link AbstractLabelDefinitionProvider} with the given adapter function.
   *
   * @param labelAndPropertyProviderAdapterFunction
   */
  public AbstractLabelDefinitionProvider(
      Function<HGNode, ILabelAndPropertyProvider> labelAndPropertyProviderAdapterFunction) {
    super(labelAndPropertyProviderAdapterFunction);
  }

  /**
   *
   */
  public AbstractLabelDefinitionProvider() {
    super();
  }


  @Override
  public final ILabelDefinition getLabelDefinition(HGNode node) {

    DefaultLabelDefinition defaultLabelDefinition = new DefaultLabelDefinition();

    processor().processLabelDefinition(node, defaultLabelDefinition);

    return defaultLabelDefinition;
  }

    protected abstract ILabelDefinitionProcessor createLabelDefinitionProcessor();

    private ILabelDefinitionProcessor processor() {

    if (this._processor == null) {
      this._processor = createLabelDefinitionProcessor();
      checkNotNull(this._processor);
    }

    return this._processor;
  }
}
