package org.slizaa.hierarchicalgraph.graphdb.testfwk.mapping;

import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ILabelDefinitionProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.AbstractLabelDefinitionProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.dsl.ILabelDefinitionProcessor;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 *
 */
public class SimpleJTypeLabelProvider extends AbstractLabelDefinitionProvider implements ILabelDefinitionProvider {

  /**
   * {@inheritDoc}
   */
  @Override
  protected ILabelDefinitionProcessor createLabelDefinitionProcessor() {

    //@formatter:off
    return exclusiveChoice().

        // Module
        when(nodeHasLabel("Module")).then(layoutModule()).

        // Package
        when(nodeHasLabel("Directory")).then(layoutDirectory()).

        // Resource
        when(nodeHasLabel("Resource")).then(layoutResource()).

        // Type
        when(nodeHasLabel("Type")).then(layoutType()).

        // all other nodes
        otherwise(setBaseImage("icons/jar_obj.png")).
              and(setLabelText(propertyValue("name")));

    //@formatter:on
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  protected ILabelDefinitionProcessor layoutModule() {
    return setBaseImage("icons/jar_obj.png").and(setLabelText(propertyValue("name")));
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  protected ILabelDefinitionProcessor layoutDirectory() {

    //@formatter:off
    return exclusiveChoice().

        // Packages
        when(nodeHasLabel("Package")).
          then(setBaseImage("icons/package_obj.png").
           and(setLabelText(propertyValue("fqn", str -> str.replace('/', '.'))))).

        // Directories
        otherwise(setBaseImage("icons/fldr_obj.png")).
              and(setLabelText(propertyValue("fqn")));
    //@formatter:on
  }

  private ILabelDefinitionProcessor layoutResource() {

    //@formatter:off
    return executeAll(

        exclusiveChoice().
          when(nodeHasLabel("ClassFile")).then(setBaseImage("icons/classf_obj.png")).
          otherwise(setBaseImage("icons/file_obj.png")),

        setLabelText(propertyValue("name"))
    );
    //@formatter:on
  }

  /**
   * <p>
   * </p>
   *
   * @return
   */
  protected ILabelDefinitionProcessor layoutType() {

    //@formatter:off
    return executeAll(

        setLabelText(propertyValue("name")),

        when(nodeHasProperty("final")).
          then(setOverlayImage("icons/class_obj.png", OverlayPosition.TOP_RIGHT)),

        when(nodeHasLabel("Class")).
          then(setBaseImage("icons/class_obj.png")),

        when(nodeHasLabel("Annotation")).
          then(setBaseImage("icons/annotation_obj.png")),

        when(nodeHasLabel("Enum")).
          then(setBaseImage("icons/enum_obj.png")),

        when(nodeHasLabel("Interface")).
          then(setBaseImage("icons/int_obj.png"))
        );
    //@formatter:on
  }

}
