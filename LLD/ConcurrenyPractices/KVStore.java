package ConcurrenyPractices;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class KVStore<K, V> {
    private final int bucketCount;
    private final HashMap<K, V>[] buckets;
    private final ReentrantLock[] locks;

    public KVStore(int bucketCount) {
        this.bucketCount = bucketCount;
        this.buckets = new HashMap[bucketCount];
        this.locks = new ReentrantLock[bucketCount];
        for (int i = 0; i < bucketCount; i++) {
            buckets[i] = new HashMap<>();
            locks[i] = new ReentrantLock();
        }
    }

    private int getBucket(K key) {
        return (key.hashCode() & 0x7fffffff) % bucketCount;
    }

    public void put(K key, V value) {
        int bucket = getBucket(key);
        locks[bucket].lock();
        try {
            buckets[bucket].put(key, value);
        } finally {
            locks[bucket].unlock();
        }
    }

    public V get(K key) {
        int bucket = getBucket(key);
        locks[bucket].lock();
        try {
            return buckets[bucket].get(key);
        } finally {
            locks[bucket].unlock();
        }
    }

    public void remove(K key) {
        int bucket = getBucket(key);
        locks[bucket].lock();
        try {
            buckets[bucket].remove(key);
        } finally {
            locks[bucket].unlock();
        }
    }

    // ── Demo ─────────────────────────────────────────────────────────────────
    // 5 writer threads + 5 reader threads — concurrent access across striped buckets
    public static void main(String[] args) throws InterruptedException {
        System.out.println("═══ KVStore demo (striped locks, 5 writers + 5 readers) ═══");
        KVStore<String, Integer> store = new KVStore<>(8);
        List<Thread> threads = new ArrayList<>();

        // Writers
        for (int i = 1; i <= 5; i++) {
            int id = i;
            threads.add(new Thread(() -> {
                for (int j = 0; j < 4; j++) {
                    String key = "key-" + id + "-" + j;
                    store.put(key, id * 10 + j);
                    System.out.println("  Writer-" + id + " put  [" + key + " = " + (id * 10 + j) + "]");
                }
            }));
        }

        // Readers — slight delay so writers populate first
        for (int i = 1; i <= 5; i++) {
            int id = i;
            threads.add(new Thread(() -> {
                try {
                    Thread.sleep(50);
                    for (int j = 0; j < 4; j++) {
                        String key = "key-" + id + "-" + j;
                        System.out.println("  Reader-" + id + " get  [" + key + " = " + store.get(key) + "]");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.println("  → Verify: key-3-2 = " + store.get("key-3-2") + " (expected 32)");
    }
}
