# LRU Cache — Interview Deep Dives & Follow-ups

## Functional Requirements
- Create cache with a size constraint
- Support generic key-value pairs
- `get(key)` → returns value, or `null` if not found; moves key to most recently used
- `put(key, value)` → inserts or updates; if key exists, update value and move to front; if at capacity, evict LRU (tail) before inserting
- Thread-safe

## Non-Functional Requirements
- O(1) for both `get` and `put`
- Thread-safe under concurrent access

---

## Data Structure Choice

**Q: How do you achieve O(1) for both get and put?**

A: HashMap + Doubly Linked List.
- HashMap gives O(1) access to any node by key
- DLL maintains insertion/access order — head = most recently used, tail = least recently used
- Dummy head and tail sentinel nodes to simplify edge cases

---

## Design

- `Node<K, V>` — holds key, value, prev, next pointers
- `DoublyLinkedList<K, V>` — addFirst, remove, moveToFront, removeLast
- `LRUCache<K, V>` — holds `Map<K, Node<K,V>>` + DLL + capacity

---

## Common Bugs to Watch

### Bug 1 — Eviction must remove from map too
```java
// Wrong
Node<K, V> lastNode = doublyLinkedList.removeLast();
map.remove(lastNode); // removes node object, not key

// Correct
map.remove(lastNode.getKey());
```

### Bug 2 — Singleton getInstance must be static
```java
// Wrong
public LRUCache getInstance(int size) { ... }

// Correct
public static LRUCache getInstance(int size) { ... }
```

---

## Design Decision — Singleton

**Q: Do you need Singleton for LRUCache?**

A: No. A cache is a per-use-case instance. Multiple caches can coexist with different types, sizes, and purposes. Singleton would incorrectly force a single global cache. Use a plain public constructor instead.

---

## Concurrency Deep Dive

### Coarse-grained locking (current approach)
```java
public synchronized void put(K key, V value) { ... }
public synchronized void get(K key) { ... }
```
- Locks `this` — entire cache, one thread at a time
- **Downside:** High contention at scale; all threads block regardless of which key they access

### Why ConcurrentHashMap alone is not enough
- `get` does two things: map lookup + `moveToFront` (DLL mutation)
- These two operations must be atomic together
- ConcurrentHashMap only makes individual map ops atomic — doesn't protect the compound operation

### Why ReentrantReadWriteLock doesn't help
- `get` is both a read (map lookup) and a write (moveToFront on DLL)
- Every operation needs the write lock → equivalent to synchronized → no improvement

### Better approach — Segmented Locking
- Split cache into N independent segments (each is a smaller LRU cache with its own lock)
- Key is hashed to determine segment
- Threads on different segments don't block each other
- Throughput improves by factor of N
- This is how `ConcurrentHashMap` works internally
- Trade-off: significantly more complex to implement

### Interview answer
Start with coarse `synchronized` (correct and simple), then **propose** segmented locking as a scalability improvement. Explain the trade-off. You don't need to implement segmented locking unless explicitly asked.

---

## Java Memory Model — Constructor Safety

**Q: Can two threads cause a race condition by calling the constructor simultaneously?**

A: No. Each `new LRUCache(size)` creates an independent object. The **Java Memory Model (JMM)** guarantees that all writes inside a constructor complete before the object reference is published to other threads. A partially constructed object cannot be seen by another thread through a normally published reference.

**Note:** Constructors cannot be `synchronized` in Java — it's a compile error. And it's unnecessary due to JMM guarantees.

---

## Key Concepts to Know Cold

| Concept | What to know |
|---|---|
| Java Memory Model (JMM) | Guarantees constructor visibility; happens-before relationships |
| `synchronized` | Coarse-grained; locks on `this` |
| `ReentrantReadWriteLock` | Useful only when reads dominate and reads don't mutate state |
| `ConcurrentHashMap` | Uses segmented locking internally; atomic per-operation, not compound |
| Segmented locking | N locks for N partitions; reduces contention by factor of N |
| `volatile` | Ensures visibility across threads; not atomicity |

---

## Verdict
- **SDE2/L4 (4-5 YOE):** Strong Hire
- **SDE3/L5:** Borderline — gaps in catching own bugs and concurrency edge cases

### To improve to SDE3 level:
- Catch eviction + map sync bugs independently before review
- Go deeper on `ReentrantLock`, `StampedLock`, JMM visibility guarantees
- Practice implementing segmented locking