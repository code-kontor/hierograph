package org.slizaa.jqassistant.hierarchicalgraph;

import org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.AbstractQueryBasedHierarchyProvider;

public class JQAssistant_HierarchyProvider extends AbstractQueryBasedHierarchyProvider {

	@Override
	protected String[] toplevelNodeIdQueries() {
		return new String[] {
				// scanned jars directly
				"MATCH (a:Artifact:Jar) RETURN id(a) as id",
				// scanned maven projects
				"MATCH (a:Project:File:Maven:Directory)-[CREATES]->(b:Artifact:Maven:File) WHERE a.packaging = 'jar' AND (b:Main OR b:Test) RETURN id(a) as id"
		};
	}

	@Override
	protected String[] parentChildNodeIdsQueries() {
		return new String[] {
				// Artifact -> top-level Packages
				"MATCH (a:Artifact:Jar)-[:CONTAINS]->(b:Package) WHERE NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b)",
				"MATCH (a:Project:File:Maven:Directory)-[CREATES]->(b:Artifact:Maven:File) WHERE a.packaging = 'jar' AND (b:Main OR b:Test) RETURN id(a), id(b)",
				"MATCH (a:Artifact:Maven:File:Main)-[:CONTAINS]->(b:Package) where a.type = 'jar' AND NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b)",
				"MATCH (a:Artifact:Maven:File:Test)-[:CONTAINS]->(b:Package) where a.type = 'test-jar' AND NOT (:Package)-[:CONTAINS]->(b) RETURN id(a), id(b)",
				// Package -> sub-Packages
				"MATCH (a:Package)-[:CONTAINS]->(b:Package) RETURN id(a), id(b)",
				// Package -> Types (Class, Interface, Enum, Annotation, Record)
				"MATCH (a:Package)-[:CONTAINS]->(b:Type) WHERE b:Type OR b:Interface OR b:Enum OR b:Annotation OR b:Record RETURN id(a), id(b)",
		};
	}
}
