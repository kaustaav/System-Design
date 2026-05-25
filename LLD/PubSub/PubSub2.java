package PubSub;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

class Message {
    private final String id;
    private final String content;
    private final LocalDateTime timestamp;

    public Message(String id, String content, LocalDateTime timestamp) {
        this.id = id;
        this.content = content;
        this.timestamp = timestamp;
    }

    public String getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}

interface Consumer {
    String getId();

    void onMessage(Message message);
}

class CricketConsumer implements Consumer {
    private final String id;

    public CricketConsumer(String id) {
        this.id = id;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void onMessage(Message message) {
        System.out.println("New cricket update: " + message.getContent());
    }
}

class Topic {
    private final String name;
    private final Map<String, Consumer> consumerMap;
    private final ExecutorService deliveryExecutor;

    public Topic(String name, ExecutorService deliveryExecutor) {
        this.name = name;
        this.deliveryExecutor = deliveryExecutor;
        consumerMap = new ConcurrentHashMap<>();
    }

    public void addConsumer(Consumer consumer) {
        consumerMap.putIfAbsent(consumer.getId(), consumer);
    }

    public void removeConsumer(Consumer consumer) {
        consumerMap.remove(consumer.getId());
    }

    public void broadcastMessage(Message message) {
        for (Consumer consumer : consumerMap.values())
            deliveryExecutor.submit(() -> {
                try {
                    consumer.onMessage(message);
                } catch (Exception e) {
                    System.err
                            .println("Unable to deliver message: " + message.getId() + " to consuemr"
                                    + consumer.getId());
                }
            });
    }

    public String getName() {
        return name;
    }
}

public class PubSub2 {
    private static volatile PubSub2 instance;
    private final Map<String, Topic> topicMap;
    private final ExecutorService deliveryExecutor;

    private PubSub2() {
        topicMap = new ConcurrentHashMap<>();
        deliveryExecutor = new ThreadPoolExecutor(10, 50, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1000),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public static PubSub2 getInstance() {
        if (instance == null)
            synchronized (PubSub2.class) {
                if (instance == null)
                    instance = new PubSub2();
            }
        return instance;
    }

    public void addTopic(String topicName) {
        if (topicMap.containsKey(topicName))
            return;
        Topic topic = new Topic(topicName, deliveryExecutor);
        topicMap.putIfAbsent(topic.getName(), topic);
    }

    public void subscribeNewConsumerToTopic(String topicName, Consumer consumer) {
        Topic topic = topicMap.get(topicName);
        if (topic == null)
            throw new IllegalStateException("Topic not found");
        topic.addConsumer(consumer);
    }

    public void broadcastMessage(Message message, String topicName) {
        Topic topic = topicMap.getOrDefault(topicName, null);
        if (topic == null)
            throw new IllegalStateException("Topic not found");
        topic.broadcastMessage(message);
    }

    public void shutdown() {
        deliveryExecutor.shutdown();
        try {
            if (!deliveryExecutor.awaitTermination(30, TimeUnit.SECONDS))
                deliveryExecutor.shutdownNow();
        } catch (InterruptedException e) {
            deliveryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
