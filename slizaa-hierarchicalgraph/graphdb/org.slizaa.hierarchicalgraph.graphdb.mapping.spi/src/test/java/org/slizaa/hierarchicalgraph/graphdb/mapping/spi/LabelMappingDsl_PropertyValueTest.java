package org.slizaa.hierarchicalgraph.graphdb.mapping.spi;

import org.junit.Test;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.DefaultLabelDefinition;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.dsl.LabelMappingDsl;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the {@link LabelMappingDsl#propertyValue(String)} function.
 */
public class LabelMappingDsl_PropertyValueTest extends AbstractLabelMappingDslTest {

  /**
   * Tests that the {@link LabelMappingDsl#propertyValue(String)} function returns the selected value.
   */
  @Test
  public void testPropertyValue() {
    DefaultLabelDefinition labelDefinition = process(setLabelText(propertyValue("test")),
        createHGNode(Collections.singletonMap("test", "test")));
    assertThat(labelDefinition.getText()).isNotNull().isEqualTo("test");
  }

  /**
   * Tests that the {@link LabelMappingDsl#propertyValue(String)} function returns '<<UNKNOWN_PROPERTY_KEY: ...' if the value is missing.
   */
  @Test
  public void testPropertyValue_Null_1() {
    DefaultLabelDefinition labelDefinition = process(setLabelText(propertyValue("missing")), createHGNode());
    assertThat(labelDefinition.getText()).isNotNull().isEqualTo("<<UNKNOWN_PROPERTY_KEY: missing (666) >>");
  }

  /**
   * Tests that the {@link LabelMappingDsl#propertyValue(String)} function returns '<<UNKNOWN_PROPERTY_KEY: ...' if the value is missing.
   */
  @Test
  public void testPropertyValue_Null_2() {
    DefaultLabelDefinition labelDefinition = process(setLabelText(propertyValue("missing", str -> str.trim())),
        createHGNode());
    assertThat(labelDefinition.getText()).isNotNull().isEqualTo("<<UNKNOWN_PROPERTY_KEY: missing (666) >>");
  }
}
