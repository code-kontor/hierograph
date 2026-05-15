/**
 *
 */
package org.slizaa.core.boltclient;

import org.neo4j.driver.EagerResult;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.slizaa.core.boltclient.internal.gson.BoltAwareGsonFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class StatementResultToJsonConverter {

  /** - */
  private static Gson _gson = BoltAwareGsonFactory.createGson();

  /**
   * <p>
   * </p>
   *
   * @param statementResult
   * @return
   */
  public static JsonArray convertToJsonArray(EagerResult statementResult) {

    //
    JsonArray result = new JsonArray();

    //
    for (Record record : statementResult.records()) {
      JsonElement element = _gson.toJsonTree(record.asMap());
      result.add(element);
    }

    //
    return result;
  }

  /**
   * <p>
   * </p>
   *
   * @param statementResult
   * @return
   */
  public static String convertToJson(EagerResult statementResult) {

    //
    JsonArray result = new JsonArray();

    //
    for (Record record : statementResult.records()) {
      JsonElement element = _gson.toJsonTree(record.asMap());
      result.add(element);
    }

    //
    return _gson.toJson(result);
  }

  /**
   * <p>
   * Creates a new instance of type {@link StatementResultToJsonConverter}.
   * </p>
   *
   */
  private StatementResultToJsonConverter() {
    //
  }
}
