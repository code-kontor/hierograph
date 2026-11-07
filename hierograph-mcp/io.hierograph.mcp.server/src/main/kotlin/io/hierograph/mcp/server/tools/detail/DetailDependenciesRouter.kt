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
package io.hierograph.mcp.server.tools.detail

import io.hierograph.graphservice.HierarchicalGraphService
import io.hierograph.graphservice.pagination.PaginationSpec
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * The [IDetailDependencies] the detail-consuming tools actually get (it is `@Primary`): it routes each
 * call to the Parquet engine ([ParquetDetailDependencies]) when the served graph is Parquet-backed, or
 * to the Neo4j/Cypher implementation ([DetailDependenciesComponent]) otherwise — so `detail_level=detail`
 * needs no reachable database in `parquet` mode.
 */
@Primary
@Component
class DetailDependenciesRouter(
    private val cypher: DetailDependenciesComponent,
    private val parquet: ParquetDetailDependencies,
    private val graphService: HierarchicalGraphService,
) : IDetailDependencies {

    override fun detailDependencies(
        fromId: Long,
        toId: Long,
        relationship: String?,
        limit: Int?,
        cursor: String?,
        spec: PaginationSpec,
    ): Map<String, Any?> =
        if (graphService.isParquetBacked) {
            parquet.detailDependencies(fromId, toId, relationship, limit, cursor, spec)
        } else {
            cypher.detailDependencies(fromId, toId, relationship, limit, cursor, spec)
        }
}
