package dev.jefe.mailsorter.application.service;

import dev.jefe.mailsorter.domain.model.Classification;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Small thread-safe LRU cache with TTL for classification results.
 * Never holds a lock while classifying: callers do get() -> classify -> put().
 * Only successful classifications should ever be put().
 */
public final class ClassificationCache {

    /** classifierVersion = model + prompt version, so changing either invalidates old entries. */
    public record Key(String accountId, String emailId, String classifierVersion) {}

    private record CacheEntry(Classification value, long expiresAtNanos) {}

    private final long ttlNanos;
    private final Map<Key, CacheEntry> map;

    public ClassificationCache(int maxEntries, Duration ttl) {
        this.ttlNanos = ttl.toNanos();
        this.map = new LinkedHashMap<>(256, 0.75f, true) { // access order = LRU
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, CacheEntry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** Returns the cached value, or null on miss/expiry. */
    public synchronized Classification get(Key key) {
        CacheEntry e = map.get(key);
        if (e == null) return null;
        if (System.nanoTime() - e.expiresAtNanos() >= 0) {
            map.remove(key);
            return null;
        }
        return e.value();
    }

    public synchronized void put(Key key, Classification value) {
        map.put(key, new CacheEntry(value, System.nanoTime() + ttlNanos));
    }

    public synchronized void remove(Key key) {
        map.remove(key);
    }

    public synchronized void clear() {
        map.clear();
    }
}