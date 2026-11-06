/*
 * Copyright 2026 Gerd Wuetherich
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.hierograph.graphstore.jqa.detail

import io.hierograph.boltclient.IBoltClient

/**
 * The Neo4j comparison baseline for the Parquet detail engine: runs the *same* member-edge patterns
 * (`JqaDetailExporter.detailRefsCypher`) directly against the live store, scoped to a set of source
 * type FQNs. Because both sides are fed the identical inheritance-expanded FQN set from [DetailScope],
 * the only variable is the engine — Parquet read vs. live Cypher — so the diff isolates export fidelity
 * + the engine's filtering, and the benchmark compares like for like.
 */
class JqaDetailReference(private val boltClient: IBoltClient) {

    fun query(fromTypes: Set<String>): List<DetailRef> {
        val cypher = JqaDetailExporter.detailRefsCypher("AND ft.fqn IN \$fromFqns")
        val params = mapOf<String, Any>("fromFqns" to fromTypes.toList())
        return boltClient.syncExecCypherQuery(cypher, params).records().map { r ->
            DetailRef(
                fromKey = r.get("from_key").asString(),
                fromType = r.get("from_type").asString(),
                toKey = r.get("to_key").asString(),
                toType = r.get("to_type").asString(),
                relationship = r.get("relationship").asString(),
                line = r.get("line").let { if (it.isNull) null else it.asInt() },
            )
        }
    }
}
