package org.slizaa.jqassistant.hierarchicalgraph;

import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ILabelDefinitionProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.AbstractLabelDefinitionProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.dsl.ILabelDefinitionProcessor;

public class JQAssistant_LabelProvider extends AbstractLabelDefinitionProvider
		implements ILabelDefinitionProvider, JQAssistant_Constants {

	@Override
	protected ILabelDefinitionProcessor createLabelDefinitionProcessor() {

		// @formatter:off
		return exclusiveChoice().

				// Artifact
				when(nodeHasLabel("Artifact")).then(
						setBaseImage(ICONS_JAR_OBJ_SVG).and(setLabelText(propertyValue("name")))).

				// Package
				when(nodeHasLabel("Package")).then(
						setBaseImage(ICONS_PACKAGE_OBJ_SVG).and(setLabelText(propertyValue("name", str -> str.replace('/', '.'))))).

				// Class
				when(nodeHasLabel("Class")).then(
						setBaseImage(ICONS_CLASS_OBJ_SVG).and(setLabelText(propertyValue("name")))).

				// Annotation
				when(nodeHasLabel("Annotation")).then(
						setBaseImage(ICONS_ANNOTATION_OBJ_SVG).and(setLabelText(propertyValue("name")))).

				// Enum
				when(nodeHasLabel("Enum")).then(
						setBaseImage(ICONS_ENUM_OBJ_SVG).and(setLabelText(propertyValue("name")))).

				// Interface
				when(nodeHasLabel("Interface")).then(
						setBaseImage(ICONS_INT_OBJ_SVG).and(setLabelText(propertyValue("name")))).

				// all other nodes
				otherwise(setBaseImage(ICONS_FLDR_OBJ_SVG).and(setLabelText(propertyValue("fqn"))));

		// @formatter:on
	}
}
