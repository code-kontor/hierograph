package org.slizaa.core.boltclient.internal.gson;

import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class BoltAwareGsonFactory {

  public static Gson createGson() {
    return new GsonBuilder().disableHtmlEscaping().registerTypeHierarchyAdapter(Value.class, new InternalValueAdapter())
        .registerTypeHierarchyAdapter(Node.class, new InternalNodeAdapter())
        .registerTypeHierarchyAdapter(Relationship.class, new InternalRelationshipAdapter())
        .registerTypeHierarchyAdapter(Path.class, new InternalPathAdapter()).create();
  }
}
