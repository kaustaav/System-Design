# LRU Cache — Entities

---

## Node<K, V>
```
final K key
V value                           // mutable — updated on put of existing key
Node<K,V> prev, next              // package-private — manipulated directly by DoublyLinkedList

getKey()
getValue()
setValue(V value)
```

---

## DoublyLinkedList<K, V>
```
Node<K,V> head, tail              // sentinel/dummy nodes — initialized at construction
                                  // head.next = tail, tail.prev = head (empty list invariant)

void addFirst(Node node)          // insert after head — marks node as most recently used
void remove(Node node)            // unlink node from its current position
void moveToFront(Node node)       // remove(node) + addFirst(node) — O(1) recency update
Node<K,V> removeLast()            // remove + return tail.prev (LRU node); null if list empty
```
Note: sentinel nodes eliminate null checks — removeLast checks `tail.prev == head` instead of null.

---

## LRUCache<K, V>
```
final Map<K, Node<K,V>> map       // HashMap — O(1) key lookup
final DoublyLinkedList<K,V> doublyLinkedList
final int size                    // capacity

synchronized void put(K key, V value)
    // key exists:  update node.value + moveToFront
    // key absent, at capacity: removeLast → map.remove(evictedKey) → new node → map.put + addFirst
    // key absent, under capacity: new node → map.put + addFirst

synchronized V get(K key)
    // miss: return null
    // hit:  moveToFront + return node.getValue()
```

---

## Key Design Notes
| Concern | Solution |
|---|---|
| O(1) eviction of LRU element | DoublyLinkedList.removeLast() — direct pointer to tail.prev |
| O(1) recency promotion | DoublyLinkedList.moveToFront() — pointer surgery, no traversal |
| O(1) key lookup | HashMap maps key → Node directly |
| Sentinel head/tail | Eliminates null edge cases for empty list and single-element list |
| Thread safety | `synchronized` on both `put` and `get` — coarse-grained lock on the cache instance |

---

## Flow Summary
- **get (hit):** map.get(key) → node found → moveToFront (promote to MRU) → return value
- **get (miss):** map.get(key) → null → return null
- **put (existing key):** map.get(key) → update node.value → moveToFront
- **put (new key, under capacity):** new Node → map.put + DLL.addFirst
- **put (new key, at capacity):** DLL.removeLast → map.remove(evictedKey) → new Node → map.put + DLL.addFirst