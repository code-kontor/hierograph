package io.hierograph.hierarchicalgraph.core.model.internal

class InvalidatableLazy<T>(private val initializer: () -> T) {
    private var initialized = false
    private var value: T? = null

    fun get(): T {
        if (!initialized) {
            value = initializer()
            initialized = true
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    fun invalidate() {
        initialized = false
        value = null
    }
}
