package org.slizaa.hierarchicalgraph.graphdb.mapping.cypher;

import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IHierarchyDefinitionProvider;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IHierarchyDefinitionProvider.ParentChildNode;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IHierarchyDefinitionProvider.RootNode;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;

public abstract class AbstractQueryBasedHierarchyProvider implements IHierarchyDefinitionProvider, IBoltClientAware {

  private List<RootNode> _toplevelNodeIds;

  private List<ParentChildNode> _parentChildNodes;

  @Override
  public void initialize(IBoltClient boltClient) throws Exception {

    checkNotNull(boltClient);

    this._toplevelNodeIds = new ArrayList<>();
    for (String query : toplevelNodeIdQueries()) {
      this._toplevelNodeIds.addAll(
          boltClient.asyncExecCypherQueryAndTransformResult(query, result -> result.list(r -> new RootNode(
              r.get(0).asLong(),
              parseKind(r.get(1).asString())
          ))).get());
    }

    this._parentChildNodes = new ArrayList<>();
    for (String query : parentChildNodeIdsQueries()) {
      this._parentChildNodes.addAll(
          boltClient.asyncExecCypherQueryAndTransformResult(query, result -> result.list(r -> new ParentChildNode(
              r.get(0).asLong(),
              r.get(1).asLong(),
              parseKind(r.get(2).asString())
          ))).get());
    }
  }

  @Override
  public List<RootNode> getToplevelNodeIds() throws Exception {
    return this._toplevelNodeIds;
  }

  @Override
  public List<ParentChildNode> getParentChildNodeIds() throws Exception {
    return this._parentChildNodes;
  }

  protected abstract String[] toplevelNodeIdQueries();

  protected abstract String[] parentChildNodeIdsQueries();

  /**
   * Converts a kind string read from a Cypher result into the kind object
   * stored on the node. Subclasses override this to return an enum value
   * (e.g. {@code JavaNodeKind.fromValue(kindString)}). The default returns
   * the string as-is.
   */
  protected Object parseKind(String kindString) {
    return kindString;
  }
}
