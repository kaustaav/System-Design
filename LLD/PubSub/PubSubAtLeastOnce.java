package PubSub;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

// ─── Message ────────────────────────────────────────────────────────────────

class MessageV2 {
    private final String content;
    private final LocalDateTime timestamp;

    public MessageV2(String content) {
        this.content = content;
        this.timestamp = LocalDateTime.now();
    }

    public String getContent() { return content; }
    public LocalDateTime getTimestamp() { return timestamp; }
}

// ─── Consumer ───────────────────────────────────────────────────────────────

interface ConsumerV2 {
    String getId();
    void onMessage(MessageV2 message); // throw to signal processing failure → retry
}

class WeatherConsumerV2 implements ConsumerV2 {
    private final String id;
    public WeatherConsumerV2(String id) { this.id = id; }

    @Override public String getId() { return id; }

    @Override
    public void onMessage(MessageV2 message) {
        System.out.println("[" + id + "] received: " + message.getContent());
    }
}

// ─── Topic ──────────────────────────────────────────────────────────────────

class TopicV2 {
    private final String name;
    private final Map<Long, MessageV2> messageLog = new ConcurrentHashMap<>();
    private final Map<String, Long> consumerOffsets = new ConcurrentHashMap<>(); // consumerId → next offset to read
    private final AtomicLong nextOffset = new AtomicLong(0); // ever-incrementing, never reused

    public TopicV2(String name) {
        this.name = name;
    }

    public String getName() { return name; }

    // Called by publisher — appends message to log
    public void publish(MessageV2 message) {
        long offset = nextOffset.getAndIncrement();
        messageLog.put(offset, message);
    }

    // Register consumer at latest offset (only new messages)
    public void registerConsumer(String consumerId) {
        consumerOffsets.putIfAbsent(consumerId, nextOffset.get());
    }

    public void removeConsumer(String consumerId) {
        consumerOffsets.remove(consumerId);
    }

    // Returns message at consumer's current offset, or null if nothing new
    public MessageV2 poll(String consumerId) {
        Long offset = consumerOffsets.get(consumerId);
        if (offset == null) throw new IllegalStateException("Consumer not registered: " + consumerId);
        return messageLog.get(offset); // null if message at this offset doesn't exist yet
    }

    // Advance offset — called only after successful onMessage
    public void commitOffset(String consumerId) {
        consumerOffsets.computeIfPresent(consumerId, (id, offset) -> offset + 1);
    }

    // Reset to beginning — consumer replays all retained messages
    public void resetOffset(String consumerId) {
        if (!consumerOffsets.containsKey(consumerId))
            throw new IllegalStateException("Consumer not registered: " + consumerId);
        long earliest = messageLog.keySet().stream().min(Long::compareTo).orElse(0L);
        consumerOffsets.put(consumerId, earliest);
    }
}

// ─── ConsumerWorker ─────────────────────────────────────────────────────────
// Runs a polling loop for one consumer on one topic.
// At-least-once: offset is committed only after successful onMessage.
// On failure, offset is NOT advanced → same message is retried next poll.

class ConsumerWorker implements Runnable {
    private final ConsumerV2 consumer;
    private final TopicV2 topic;
    private volatile boolean running = true;

    private static final int MAX_RETRIES = 3;
    private static final long POLL_INTERVAL_MS = 100;

    public ConsumerWorker(ConsumerV2 consumer, TopicV2 topic) {
        this.consumer = consumer;
        this.topic = topic;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        while (running) {
            MessageV2 message = topic.poll(consumer.getId());

            if (message == null) {
                // No new message — wait before polling again
                try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            // Attempt delivery with retries
            boolean delivered = false;
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    consumer.onMessage(message);
                    delivered = true;
                    break;
                } catch (Exception e) {
                    System.err.println("Delivery failed for consumer " + consumer.getId()
                            + " attempt " + attempt + ": " + e.getMessage());
                }
            }

            if (delivered) {
                topic.commitOffset(consumer.getId()); // advance only on success
            } else {
                // Max retries exhausted — skip message to avoid infinite loop
                // In production: send to Dead Letter Queue instead
                System.err.println("Max retries exhausted for consumer " + consumer.getId()
                        + ", skipping message: " + message.getContent());
                topic.commitOffset(consumer.getId());
            }
        }
    }
}

// ─── PubSubV2 (Broker) ──────────────────────────────────────────────────────

public class PubSubAtLeastOnce {
    private static volatile PubSubAtLeastOnce instance;
    private final Map<String, TopicV2> topicMap = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ConsumerWorker>> workers = new ConcurrentHashMap<>(); // topicName → consumerId → worker
    private final ExecutorService workerExecutor = new ThreadPoolExecutor(
            10, 50, 60, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private PubSubAtLeastOnce() {}

    public static PubSubAtLeastOnce getInstance() {
        if (instance == null)
            synchronized (PubSubAtLeastOnce.class) {
                if (instance == null)
                    instance = new PubSubAtLeastOnce();
            }
        return instance;
    }

    public void addTopic(String topicName) {
        topicMap.putIfAbsent(topicName, new TopicV2(topicName));
        workers.putIfAbsent(topicName, new ConcurrentHashMap<>());
    }

    // Subscribe: register consumer + start its polling worker
    public void subscribeConsumer(String topicName, ConsumerV2 consumer) {
        TopicV2 topic = topicMap.get(topicName);
        if (topic == null) throw new IllegalStateException("Topic not found: " + topicName);

        topic.registerConsumer(consumer.getId());

        ConsumerWorker worker = new ConsumerWorker(consumer, topic);
        workers.get(topicName).put(consumer.getId(), worker);
        workerExecutor.submit(worker);
    }

    // Unsubscribe: stop worker + deregister consumer
    public void unsubscribeConsumer(String topicName, String consumerId) {
        TopicV2 topic = topicMap.get(topicName);
        if (topic == null) throw new IllegalStateException("Topic not found: " + topicName);

        ConsumerWorker worker = workers.get(topicName).remove(consumerId);
        if (worker != null) worker.stop();

        topic.removeConsumer(consumerId);
    }

    public void publish(String topicName, MessageV2 message) {
        TopicV2 topic = topicMap.get(topicName);
        if (topic == null) throw new IllegalStateException("Topic not found: " + topicName);
        topic.publish(message);
    }

    public void resetOffset(String topicName, String consumerId) {
        TopicV2 topic = topicMap.get(topicName);
        if (topic == null) throw new IllegalStateException("Topic not found: " + topicName);
        topic.resetOffset(consumerId);
    }

    public void shutdown() {
        // Stop all workers first
        workers.values().forEach(topicWorkers ->
                topicWorkers.values().forEach(ConsumerWorker::stop));

        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(30, TimeUnit.SECONDS))
                workerExecutor.shutdownNow();
        } catch (InterruptedException e) {
            workerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

// ─── Demo ───────────────────────────────────────────────────────────────────

class MainV2 {
    public static void main(String[] args) throws InterruptedException {
        PubSubAtLeastOnce broker = PubSubAtLeastOnce.getInstance();

        broker.addTopic("weather");

        ConsumerV2 c1 = new WeatherConsumerV2("user-1");
        ConsumerV2 c2 = new WeatherConsumerV2("user-2");

        broker.subscribeConsumer("weather", c1);
        broker.subscribeConsumer("weather", c2);

        broker.publish("weather", new MessageV2("Storm warning issued"));
        broker.publish("weather", new MessageV2("Flood alert in effect"));

        Thread.sleep(500); // let workers process

        // Replay from beginning
        broker.resetOffset("weather", "user-1");
        Thread.sleep(500); // user-1 gets both messages again — at-least-once demonstrated

        Runtime.getRuntime().addShutdownHook(new Thread(broker::shutdown));
    }
}
