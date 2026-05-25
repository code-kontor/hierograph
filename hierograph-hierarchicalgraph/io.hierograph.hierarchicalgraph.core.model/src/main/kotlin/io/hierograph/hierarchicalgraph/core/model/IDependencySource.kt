package io.hierograph.hierarchicalgraph.core.model

interface IDependencySource {
    val identifier: Any
    var dependency: HGCoreDependency?
}
