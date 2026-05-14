# Pub-Sub System — Interview Deep Dives & Follow-ups

## Functional Requirements
- Create topics dynamically
- Publishers send messages to a topic
- Consumers subscribe/unsubscribe to topics
- All subscribers of a topic receive every published message
- Delivery is asynchronous — publisher does not block

## Non-Functional Requirements
- Thread-safe subscription and delivery
- Isolated consumer failures — one bad consumer must not affect others
- Bounded resource usage — no unbounded thread or memory growth
- Graceful shutdown with in-flight message drain

---

## Design

- `Message` — immutable value object with content and timestamp
- `Consumer` (interface) — `getId()` + `onMessage(Message)`
- `Topic` — holds `Map<String, Consumer>` + `ExecutorService`; broadcasts asynchronously
- `PubSub` (Singleton broker) — manages topics; exposes subscribe/unsubscribe/broadcast/shutdown

---

## Key Design Decisions

### Why ID-keyed Map instead of Set for consumers
- `Set` uses `equals()`/`hashCode()` — defaults to object identity
- Two `new WeatherAlertConsumer("user-1")` instances would be treated as different subscribers → duplicate delivery
- `Map<String, Consumer>` keyed by ID makes identity explicit and unambiguous
- `putIfAbsent` makes subscribe idempotent; `remove(id)` decouples unsubscribe from holding the original object reference

### Why `ArrayBlockingQueue` over `LinkedBlockingQueue`
- `LinkedBlockingQueue` (default unbounded) grows forever under load → OOM
- `ArrayBlockingQueue(n)` applies backpressure — when full, `CallerRunsPolicy` slows down the publisher naturally

### Why `CallerRunsPolicy` over `AbortPolicy`
- `AbortPolicy` throws an exception and drops the message silently from the caller's perspective
- `CallerRunsPolicy` makes the publishing thread do the delivery work itself — slows it down but doesn't lose the message

### Why async delivery via `ExecutorService`
- Publisher should not block waiting for slow consumers
- Each consumer gets its own task in the thread pool — one slow consumer doesn't delay others

---

## Common Bugs to Watch

### Bug 1 — `println(message)` prints object reference
```java
// Wrong
System.out.println(message);

// Correct
System.out.println(message.getContent());
```

### Bug 2 — TOCTOU on topic lookup
```java
// Wrong — two separate map operations, not atomic
if (!topicMap.containsKey(topicName)) throw ...
topicMap.get(topicName).addConsumer(consumer);

// Correct — single lookup
Topic topic = topicMap.get(topicName);
if (topic == null) throw ...
topic.addConsumer(consumer);
```

### Bug 3 — Silent exception swallowing in submit()
```java
// Wrong — exception stored in Future, never surfaced
deliveryExecutor.submit(() -> consumer.onMessage(message));

// Correct — catch inside lambda
deliveryExecutor.submit(() -> {
    try {
        consumer.onMessage(message);
    } catch (Exception e) {
        System.err.println("Failed to deliver to consumer " + consumer.getId() + ": " + e.getMessage());
    }
});
```

### Bug 4 — Inconsistent locking
- If `subscribe` is synchronized, `unsubscribe` must be too
- Partial locking gives false safety and introduces subtle races

---

## Concurrency Deep Dive

### Why `ConcurrentHashMap` is enough for `topicMap`
- `putIfAbsent` is atomic — safe for concurrent `addTopic` calls
- Single `get()` followed by null check is safe — no compound operation involved

### Why `ConcurrentHashMap` for `consumerMap` inside Topic
- `addConsumer`, `removeConsumer`, and `broadcastMessage` (iterating `values()`) can happen concurrently
- `ConcurrentHashMap` guarantees safe iteration — no `ConcurrentModificationException`
- `putIfAbsent` handles idempotent subscribe atomically

### `newCachedThreadPool` is dangerous
- Unbounded thread creation — 10,000 messages/sec → 10,000 threads → OOM
- Always use `ThreadPoolExecutor` with explicit bounds in production

### Thread pool sizing rule of thumb
- CPU-bound tasks: `corePoolSize = Runtime.getRuntime().availableProcessors()`
- IO-bound tasks (consumers making DB/HTTP calls): `cores * 2` or higher
- Pub-sub consumers are typically IO-bound

---

## ThreadPoolExecutor Quick Reference

| Parameter | Our Choice | Why |
|---|---|---|
| `corePoolSize` | 10 | Always-ready threads for steady-state load |
| `maximumPoolSize` | 50 | Burst headroom |
| `keepAliveTime` | 60s | Extra threads die after idle |
| Queue | `ArrayBlockingQueue(1000)` | Bounded — prevents OOM |
| Rejection | `CallerRunsPolicy` | Backpressure over message loss |

---

## Shutdown Lifecycle

```java
public void shutdown() {
    deliveryExecutor.shutdown();                          // stop accepting new tasks
    try {
        if (!deliveryExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
            deliveryExecutor.shutdownNow();               // force if drain times out
        }
    } catch (InterruptedException ie) {
        deliveryExecutor.shutdownNow();
        Thread.currentThread().interrupt();               // restore interrupt flag
    }
}
```

Register via JVM shutdown hook on the Singleton:
```java
Runtime.getRuntime().addShutdownHook(new Thread(broker::shutdown));
```

**What happens if `broadcastMessage()` is called after `shutdown()`?**
`submit()` throws `RejectedExecutionException`. Guard with `deliveryExecutor.isShutdown()` check before submitting.

---

## Follow-up Questions to Expect

**Q: What delivery guarantee does this system provide?**
A: At-most-once. Messages are fire-and-forget. No ack, no retry, no persistence. A consumer that crashes mid-delivery loses the message.

**Q: How would you support late subscribers receiving past messages?**
A: Add a message log (e.g., `List<Message>`) per topic. Replay on subscribe. Adds complexity around retention policy and memory bounds.

**Q: What if one topic has 1000 subscribers and another has 2?**
A: With a shared executor, the 1000-subscriber topic can starve the 2-subscriber topic. Fix: per-topic executor, or priority queuing.

**Q: How would you add a Publisher abstraction?**
A: `Publisher` owns a `topicName` and is the only entity that can call `broadcastMessage` on that topic. Enforces access control — random callers can't publish to arbitrary topics.

---

## Verdict
- **SDE2/L4 (4-5 YOE):** Lean No Hire initially; Hire after iteration
- Caught bugs only after prompting — needs to proactively identify TOCTOU, unbounded pool, and object identity issues

### To reach SDE3/L5:
- Independently catch `equals`/`hashCode` trap on collections
- Know `ThreadPoolExecutor` parameters without prompting
- Proactively propose `Publisher` abstraction and delivery guarantees
- Discuss at-least-once vs at-most-once vs exactly-once tradeoffs
