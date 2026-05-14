package PubSub;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.*;

class Message {
    private final String content;
    private final LocalDateTime timeStamp;

    public Message(String content) {
        this.content = content;
        this.timeStamp = LocalDateTime.now();
    }

    public String getContent() {
        return content;
    }

    public LocalDateTime getTimeStamp() {
        return timeStamp;
    }
}

interface Consumer {
    public String getId();
    public void onMessage(Message message);
}

class WeatherAlertConsumer implements Consumer {
    private final String id;
    public WeatherAlertConsumer(String id) {
        this.id = id;
    }
    @Override
    public String getId() {
        return id;
    }

    @Override
    public void onMessage(Message message) {
        System.out.println(message.getContent());
    }
}

class CricketAlertConsumer implements Consumer {
    private final String id;
    public CricketAlertConsumer(String id) {
        this.id = id;
    }
    @Override
    public String getId() {
        return id;
    }

    @Override
    public void onMessage(Message message) {
        System.out.println(message.getContent());
    }
}

class Topic {
    private final String name;
    private final Map<String, Consumer> consumerMap;
    private final ExecutorService deliveryExecutor;

    public Topic(String name, ExecutorService deliveryExecutor) {
        this.name = name;
        this.deliveryExecutor = deliveryExecutor;
        this.consumerMap = new ConcurrentHashMap<>();
    }

    public String getName() {
        return name;
    }

    public void addConsumer(Consumer consumer) {
        consumerMap.putIfAbsent(consumer.getId(), consumer);
    }

    public void removeConsumer(String consumerId) {
        consumerMap.remove(consumerId);
    }

    public void broadcastMessage(Message message) {
        for(Consumer consumer : consumerMap.values()) {
            deliveryExecutor.submit(() -> {
                try {
                    consumer.onMessage(message);
                } catch (Exception e) {
                    System.err.println("Failed to deliver to consumer " + consumer.getId() + message.getContent());
                }
            });
        }
    }
}

public class PubSub {
    private static volatile PubSub instance;
    private final ExecutorService deliveryExecutor;
    private final Map<String, Topic> topicMap;

    private PubSub() {
        this.deliveryExecutor = new ThreadPoolExecutor(10, 50, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1000), new ThreadPoolExecutor.CallerRunsPolicy());
        this.topicMap = new ConcurrentHashMap<>();
    }

    public static PubSub getInstance() {
        if(instance == null)
            synchronized (PubSub.class) {
                if(instance == null)
                    instance = new PubSub();
            }
        return instance;
    }

    public void addTopic(String topicName) {
        topicMap.putIfAbsent(topicName, new Topic(topicName, deliveryExecutor));
    }

    public void subscribeConsumer(String topicName, Consumer consumer) {
        Topic topic = topicMap.get(topicName);
        if(topic == null)
            throw new IllegalStateException("Topic not found");
        topic.addConsumer(consumer);
    }

    public void unsubscribeConsumer(String topicName, String consumerId) {
        Topic topic = topicMap.get(topicName);
        if(topic == null)
            throw new IllegalStateException("Topic not found");
        topic.removeConsumer(consumerId);
    }

    public void broadcastMessage(Message message, String topicName) {
        Topic topic = topicMap.get(topicName);
        if(topic == null)
            throw new IllegalStateException("Topic not found");
        topic.broadcastMessage(message);
    }

    public void shutdown() {
        deliveryExecutor.shutdown();
        try {
            if (!deliveryExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                deliveryExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            deliveryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

class Main {
    public static void main(String[] args) {
        PubSub broker = PubSub.getInstance();

        // Create topics
        broker.addTopic("weather");
        broker.addTopic("cricket");

        // Create consumers
        Consumer w1 = new WeatherAlertConsumer("weather-user-1");
        Consumer w2 = new WeatherAlertConsumer("weather-user-2");
        Consumer c1 = new CricketAlertConsumer("cricket-user-1");

        // Subscribe
        broker.subscribeConsumer("weather", w1);
        broker.subscribeConsumer("weather", w2);
        broker.subscribeConsumer("cricket", c1);

        // Publish messages
        broker.broadcastMessage(new Message("Heavy rain expected today"), "weather");
        broker.broadcastMessage(new Message("India won by 6 wickets!"), "cricket");

        // w2 unsubscribes
        broker.unsubscribeConsumer("weather", "weather-user-2");
        broker.broadcastMessage(new Message("Thunderstorm warning issued"), "weather");
        // Only w1 should receive this

        // Shutdown gracefully
        Runtime.getRuntime().addShutdownHook(new Thread(broker::shutdown));
    }
}
