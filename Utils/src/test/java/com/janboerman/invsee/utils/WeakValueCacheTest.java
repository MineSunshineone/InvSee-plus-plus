package com.janboerman.invsee.utils;

import org.junit.jupiter.api.Test;
import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WeakValueCacheTest {
    @Test void equalKeysShareOneValue() {
        WeakValueCache<String, Object> cache = new WeakValueCache<>();
        Object original = new Object();
        assertSame(original, cache.putIfAbsent(new String("target"), original));
        assertSame(original, cache.putIfAbsent(new String("target"), new Object()));
        assertSame(original, cache.get(new String("target")));
    }

    @Test void staleQueueEntryCannotEvictReplacement() throws Exception {
        WeakValueCache<String, Object> cache = new WeakValueCache<>();
        Object old = new Object();
        cache.put("target", old);
        Reference<?> reference = reference(cache, "target");
        Object replacement = new Object();
        cache.put("target", replacement);
        reference.clear();
        reference.enqueue();
        assertSame(replacement, cache.get("target"));
    }

    @Test void clearedValueCanBeReplaced() throws Exception {
        WeakValueCache<String, Object> cache = new WeakValueCache<>();
        Object old = new Object();
        cache.put("target", old);
        reference(cache, "target").clear();
        Object replacement = new Object();
        assertSame(replacement, cache.putIfAbsent("target", replacement));
    }

    @Test void concurrentPublicationHasOneWinner() throws Exception {
        WeakValueCache<String, Object> cache = new WeakValueCache<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            java.util.List<Future<Object>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 32; i++) results.add(pool.submit(() -> {
                start.await();
                return cache.putIfAbsent("target", new Object());
            }));
            start.countDown();
            Object winner = results.get(0).get(5, TimeUnit.SECONDS);
            for (Future<Object> result : results) assertSame(winner, result.get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }

    private static Reference<?> reference(WeakValueCache<?, ?> cache, Object key) throws Exception {
        Field field = WeakValueCache.class.getDeclaredField("entries");
        field.setAccessible(true);
        return (Reference<?>) ((Map<?, ?>) field.get(cache)).get(key);
    }
}
