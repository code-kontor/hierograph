package io.hierograph.mcp.jqa.hierarchicalgraph

import io.hierograph.hierarchicalgraph.graphdb.model.GraphDbNodeSource

class ExtendedGraphDbNodeSource(identifier: Any, val name: String , val fqn: String) : GraphDbNodeSource(identifier) {
}