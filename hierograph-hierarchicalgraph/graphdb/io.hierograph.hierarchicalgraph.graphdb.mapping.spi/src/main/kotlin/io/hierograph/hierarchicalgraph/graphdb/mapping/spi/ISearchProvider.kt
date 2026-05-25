package io.hierograph.hierarchicalgraph.graphdb.mapping.spi

interface ISearchProvider {
    fun search(name: String, kindFilter: List<String>?, limit: Int): List<SearchResult>
}

data class SearchResult(
    val nodeId: Long,
    val name: String,
    val qualifiedName: String,
    val kind: String
)
