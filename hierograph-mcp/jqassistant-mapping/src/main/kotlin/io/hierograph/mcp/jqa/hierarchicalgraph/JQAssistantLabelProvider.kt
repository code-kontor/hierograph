package io.hierograph.mcp.jqa.hierarchicalgraph

import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.ILabelDefinitionProvider
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.AbstractLabelDefinitionProvider
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.dsl.ILabelDefinitionProcessor

class JQAssistantLabelProvider : AbstractLabelDefinitionProvider(), ILabelDefinitionProvider {

    override fun createLabelDefinitionProcessor(): ILabelDefinitionProcessor =
        exclusiveChoice()
            .`when`(nodeHasLabel("Artifact")).then(
                setBaseImage(JQAssistantConstants.ICONS_JAR_OBJ_SVG)
                    .and(setLabelText(propertyValue("name")))
            )
            .`when`(nodeHasLabel("Package")).then(
                setBaseImage(JQAssistantConstants.ICONS_PACKAGE_OBJ_SVG)
                    .and(setLabelText(propertyValue("name") { it.replace('/', '.') }))
            )
            .`when`(nodeHasLabel("Class")).then(
                setBaseImage(JQAssistantConstants.ICONS_CLASS_OBJ_SVG)
                    .and(setLabelText(propertyValue("name")))
            )
            .`when`(nodeHasLabel("Annotation")).then(
                setBaseImage(JQAssistantConstants.ICONS_ANNOTATION_OBJ_SVG)
                    .and(setLabelText(propertyValue("name")))
            )
            .`when`(nodeHasLabel("Enum")).then(
                setBaseImage(JQAssistantConstants.ICONS_ENUM_OBJ_SVG)
                    .and(setLabelText(propertyValue("name")))
            )
            .`when`(nodeHasLabel("Interface")).then(
                setBaseImage(JQAssistantConstants.ICONS_INT_OBJ_SVG)
                    .and(setLabelText(propertyValue("name")))
            )
            .otherwise(
                setBaseImage(JQAssistantConstants.ICONS_FLDR_OBJ_SVG)
                    .and(setLabelText(propertyValue("fqn")))
            )
}
