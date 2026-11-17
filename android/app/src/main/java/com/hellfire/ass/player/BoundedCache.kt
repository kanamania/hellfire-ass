package com.hellfire.ass.player

/** Insertion-order cache that drops the oldest entry past [max]. Used for scrub frames. */
class BoundedCache<K, V>(private val max: Int) {
    init { require(max > 0) }
    private val map = object : LinkedHashMap<K, V>(max, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }
    fun get(key: K): V? = map[key]
    fun put(key: K, value: V) { map[key] = value }
    fun size(): Int = map.size
    fun contains(key: K) = map.containsKey(key)
}
