package org.slizaa.jqassistant.hierarchicalgraph;

import org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.AbstractQueryBasedDependencyProvider;

public class JQAssistant_DependencyProvider extends AbstractQueryBasedDependencyProvider {

	@Override
	protected void initialize() {

		addSimpleDependencyDefinitions("MATCH (t1:Type)-[r:DEPENDS_ON]->(t2:Type) RETURN id(t1), id(t2), id(r), type(r)");
//		addSimpleDependencyDefinitions("MATCH (t1:Type)-[r:EXTENDS]->(t2:Type) RETURN id(t1), id(t2), id(r), type(r)");
//		addSimpleDependencyDefinitions("MATCH (t1:Type)-[r:IMPLEMENTS]->(t2:Type) RETURN id(t1), id(t2), id(r), type(r)");
//		addSimpleDependencyDefinitions("MATCH (t1:Type)-[r:ANNOTATED_BY]->(t2:Type) RETURN id(t1), id(t2), id(r), type(r)");
	}
}
