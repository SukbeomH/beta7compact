package dev.sukbeomh.beta7compact.cache

/** Connection-scoped, byte-bounded LRU. Evicted values may still be displayed by the UI. */
class ThumbnailCache<T>(private val maxBytes: Int, private val sizeOf: (T) -> Int) {
    private val entries = LinkedHashMap<String, T>(16, 0.75f, true)
    private var bytes = 0L
    var generation: Long = 0
        private set
    @Synchronized fun get(key: String): T? = entries[key]
    @Synchronized fun token(): Long = generation
    @Synchronized fun put(key: String, value: T, expectedGeneration: Long) {
        if (generation != expectedGeneration) return
        val size = sizeOf(value)
        if (size <= 0 || size > maxBytes) return
        entries.remove(key)?.let { bytes -= sizeOf(it) }
        entries[key] = value
        bytes += size
        val iterator = entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            bytes -= sizeOf(iterator.next().value)
            iterator.remove()
        }
    }
    @Synchronized fun retain(keys: Set<String>, expectedGeneration: Long) {
        if (generation != expectedGeneration) return
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.key !in keys) { bytes -= sizeOf(item.value); iterator.remove() }
        }
    }
    @Synchronized fun clear() { entries.clear(); bytes = 0; generation++ }
}
