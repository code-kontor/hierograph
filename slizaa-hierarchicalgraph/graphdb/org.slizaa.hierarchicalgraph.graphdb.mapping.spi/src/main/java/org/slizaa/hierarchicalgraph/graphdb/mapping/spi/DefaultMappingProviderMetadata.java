/**
 *
 */
package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IMappingProvider.IMappingProviderMetadata;

import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;

public class DefaultMappingProviderMetadata implements IMappingProviderMetadata {

  private String              _identifier;

  private String              _name;

  private String              _description;

  private Map<String, String> _categories;

  /**
   * <p>
   * Creates a new instance of type {@link DefaultMappingProviderMetadata}.
   * </p>
   *
   * @param identifier
   * @param name
   */
  public DefaultMappingProviderMetadata(String identifier, String name) {
    this(identifier, name, null);
  }

  /**
   * <p>
   * Creates a new instance of type {@link DefaultMappingProviderMetadata}.
   * </p>
   *
   * @param identifier
   * @param name
   * @param description
   */
  public DefaultMappingProviderMetadata(String identifier, String name, String description) {
    this._identifier = checkNotNull(identifier);
    this._name = checkNotNull(name);
    this._description = description;
    this._categories = new HashMap<>();
  }

  /**
   * <p>
   * Creates a new instance of type {@link DefaultMappingProviderMetadata}.
   * </p>
   *
   * @param identifier
   * @param name
   * @param description
   * @param categories
   */
  public DefaultMappingProviderMetadata(String identifier, String name, String description,
      Map<String, String> categories) {
    this._identifier = checkNotNull(identifier);
    this._name = checkNotNull(name);
    this._description = description;
    this._categories = categories != null ? new HashMap<>(categories) : new HashMap<>();
  }

    public void setCategoryValues(Map<String, String> categoryValues) {
    this._categories.clear();
    this._categories.putAll(categoryValues);
  }

    public void setDescription(String description) {
    this._description = description;
  }

  @Override
  public String getIdentifier() {
    return this._identifier;
  }

  @Override
  public String getName() {
    return this._name;
  }

  @Override
  public String getDescription() {
    return this._description;
  }

  @Override
  public String[] getCategories() {
    return this._categories.keySet().toArray(new String[0]);
  }

  @Override
  public String getCategoryValue(String category) {
    return this._categories.get(checkNotNull(category));
  }
}