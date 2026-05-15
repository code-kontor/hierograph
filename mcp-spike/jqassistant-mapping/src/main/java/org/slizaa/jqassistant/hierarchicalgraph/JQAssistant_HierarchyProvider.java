package org.slizaa.jqassistant.hierarchicalgraph;

import org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.AbstractQueryBasedHierarchyProvider;

public class JQAssistant_HierarchyProvider extends AbstractQueryBasedHierarchyProvider {

	@Override
	protected String[] toplevelNodeIdQueries() {
		return new String[] {
				"MATCH (a:Artifact:Jar) RETURN id(a) as id"
		};
	}

	@Override
	protected String[] parentChildNodeIdsQueries() {
		return new String[] {
				// Artifact -> top-level Packages
				"MATCH (a:Artifact:Jar)-[:CONTAINS]->(p:Package) WHERE NOT (:Package)-[:CONTAINS]->(p) RETURN id(a), id(p)",
				// Package -> sub-Packages
				"MATCH (p1:Package)-[:CONTAINS]->(p2:Package) RETURN id(p1), id(p2)",
				// Package -> Types (Class, Interface, Enum, Annotation)
				"MATCH (p:Package)-[:CONTAINS]->(t:Type:Class) RETURN id(p), id(t)",
				"MATCH (p:Package)-[:CONTAINS]->(t:Type:Interface) RETURN id(p), id(t)",
				"MATCH (p:Package)-[:CONTAINS]->(t:Type:Enum) RETURN id(p), id(t)",
				"MATCH (p:Package)-[:CONTAINS]->(t:Type:Annotation) RETURN id(p), id(t)"
		};
	}
}
