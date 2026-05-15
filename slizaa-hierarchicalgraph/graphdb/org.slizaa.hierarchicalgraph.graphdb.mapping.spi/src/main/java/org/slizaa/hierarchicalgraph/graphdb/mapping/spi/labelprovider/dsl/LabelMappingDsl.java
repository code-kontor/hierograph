package org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.dsl;

import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ILabelDefinitionProvider.OverlayPosition;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbNodeSource;

import java.net.URL;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * <p>
 *
 * </p>
 */
public class LabelMappingDsl {

  /**
   * -
   */
  private Function<HGNode, ILabelAndPropertyProvider> _labelAndPropertyProviderAdapterFunction;

  /**
   * @param labelAndPropertyProviderAdapterFunction
   */
  public LabelMappingDsl(Function<HGNode, ILabelAndPropertyProvider> labelAndPropertyProviderAdapterFunction) {
    _labelAndPropertyProviderAdapterFunction = checkNotNull(labelAndPropertyProviderAdapterFunction);
  }

  /**
   *
   */
  public LabelMappingDsl() {
    _labelAndPropertyProviderAdapterFunction = hgNode ->

        new ILabelAndPropertyProvider() {

          @Override
          public Map<String, String> getProperties() {
            return hgNode.getNodeSource(GraphDbNodeSource.class).get().getProperties().map();
          }

          @Override
          public Collection<String> getLabels() {
            return hgNode.getNodeSource(GraphDbNodeSource.class).get().getLabels();
          }
        };
  }

  /**
   * <p>
   * </p>
   *
   * @param path
   * @return
   */
  public ILabelDefinitionProcessor setBaseImage(String path) {
    return (node, labelDefinition) -> labelDefinition.setBaseImage(checkNotNull(path));
  }

  /**
   * <p>
   * </p>
   *
   * @param path
   * @param overlayPosition
   * @return
   */
  public ILabelDefinitionProcessor setOverlayImage(String path, OverlayPosition overlayPosition) {
    return (node, labelDefinition) -> labelDefinition.setOverlayImage(checkNotNull(path),
        checkNotNull(overlayPosition));
  }

  public ILabelDefinitionProcessor setLabelText(String textLabel) {
    return (node, labelDefinition) -> labelDefinition.setText(textLabel);
  }
  
  public ILabelDefinitionProcessor setIsOverlayImage(boolean isOverlayImage) {
    return (node, labelDefinition) -> labelDefinition.setIsOverlayImage(isOverlayImage);
  }

  /**
   * <p>
   * </p>
   *
   * @param string
   * @return
   */
  public Function<HGNode, Boolean> nodeHasLabel(String string) {
    return (node) -> nodeSource(node).getLabels().contains(string);
  }

  /**
   * <p>
   * </p>
   *
   * @param
   * @return
   */
  public Function<HGNode, Boolean> nodeHasProperty(String propertyName) {
    checkNotNull(propertyName);
    return (node) -> nodeSource(node).getProperties().containsKey(propertyName);
  }

  /**
   * <p>
   * </p>
   *
   * @param propertyName
   * @param value
   * @return
   */
  public Function<HGNode, Boolean> nodeHasPropertyWithValue(String propertyName, String value) {
    checkNotNull(propertyName);
    return (node) -> nodeSource(node).getProperties().containsKey(propertyName)
        && nodeSource(node).getProperties().get(propertyName).equals(value);
  }

  /**
   * <p>
   * </p>
   *
   * @param containsLabel
   * @return
   */
  public LabelMappingConditionBuilder when(Function<HGNode, Boolean> containsLabel) {
    return new LabelMappingConditionBuilder(checkNotNull(containsLabel));
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public ILabelDefinitionProcessor doNothing() {
    return (n, l) -> {
    };
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  public ExclusiveChoiceBuilder exclusiveChoice() {
    return new ExclusiveChoiceBuilder();
  }

  /**
   * <p>
   * </p>
   *
   * @param processors
   * @return
   */
  public ILabelDefinitionProcessor executeAll(ILabelDefinitionProcessor... processors) {
    checkNotNull(processors);

    //
    return (n, l) -> {
      for (ILabelDefinitionProcessor labelDefinitionProcessor : processors) {
        labelDefinitionProcessor.processLabelDefinition(n, l);
      }
    };
  }

  /**
   * <p>
   * </p>
   *
   * @param textMapper
   * @return
   */
  protected ILabelDefinitionProcessor setLabelText(Function<HGNode, String> textMapper) {
    return (hgNode, labelDefinition) -> {
      if (textMapper != null) {
        String text = textMapper.apply(hgNode);
        if (text != null) {
          labelDefinition.setText(text);
        }
        return;
      }
      labelDefinition.setText(String.format("<<LABEL_MAPPING_IS_NULL: (%s) >>", hgNode.getIdentifier()));
    };
  }

  /**
   * <p>
   * </p>
   *
   * @param key
   * @return
   */
  protected Function<HGNode, String> propertyValue(String key) {
    checkNotNull(key);
    return hgNode -> {

      // try to read the requested value
      Map<String, String> properties = nodeSource(hgNode).getProperties();
      if (properties != null) {
        String value = nodeSource(hgNode).getProperties().get(key);
        if (value != null) {
          return value;
        }
      }

      // returns the default value for unspecified labels
      return String.format("<<UNKNOWN_PROPERTY_KEY: %s (%s) >>", key, hgNode.getIdentifier());
    };
  }

  /**
   * <p>
   * </p>
   *
   * @param key
   * @param formatter
   * @return
   */
  protected Function<HGNode, String> propertyValue(String key, Function<String, String> formatter) {
    checkNotNull(key);
    checkNotNull(formatter);
    return propertyValue(key)
        .andThen(
            value -> value != null && !value.startsWith("<<UNKNOWN_PROPERTY_KEY:") ? formatter.apply(value) : value);
  }

  /**
   * <p>
   * </p>
   *
   * @param node
   * @return
   */
  private ILabelAndPropertyProvider nodeSource(HGNode node) {
    return _labelAndPropertyProviderAdapterFunction.apply(node);
  }
}
