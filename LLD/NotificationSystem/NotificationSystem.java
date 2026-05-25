package NotificationSystem;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class NotificationSystem {
}

enum NotificationType {
    EMAIL, SMS, PUSH_NOTIFICATION
}

enum NotificationStatus {
    PENDING, DELIVERED, FAILED
}

class Receiver {
    private final String id;
    private final String name;
    private Optional<String> email;
    private Optional<String> contact;
    private Optional<String> pushToken;

    public Receiver(String id, String name, String email, String contact, String pushToken) {
        this.id = id;
        this.name = name;
        this.email = Optional.ofNullable(email);
        this.contact = Optional.ofNullable(contact);
        this.pushToken = Optional.ofNullable(pushToken);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Optional<String> getEmail() {
        return email;
    }

    public Optional<String> getContact() {
        return contact;
    }

    public Optional<String> getPushToken() {
        return pushToken;
    }
}

class Notification {
    private final String id;
    private final Receiver receiver;
    private final NotificationType notificationType;
    private final String content;
    private final String subject;
    private NotificationStatus notificationStatus;

    private Notification(Builder builder) {
        this.id = builder.id;
        this.receiver = builder.receiver;
        this.notificationType = builder.notificationType;
        this.content = builder.content;
        this.subject = builder.subject;
        this.notificationStatus = NotificationStatus.PENDING;
    }

    public String getId() {
        return id;
    }

    public Receiver getReceiver() {
        return receiver;
    }

    public NotificationStatus getNotificationStatus() {
        return notificationStatus;
    }

    public NotificationType getNotificationType() {
        return notificationType;
    }

    public String getContent() {
        return content;
    }

    public String getSubject() {
        return subject;
    }

    public void setNotificationStatus(NotificationStatus notificationStatus) {
        this.notificationStatus = notificationStatus;
    }

    public static class Builder {
        private String id;
        private Receiver receiver;
        private NotificationType notificationType;
        private String content;
        private String subject;

        public Builder() {
            this.id = UUID.randomUUID().toString().substring(0, 8);
        }

        public Builder receiver(Receiver receiver) {
            this.receiver = receiver;
            return this;
        }

        public Builder notificationType(NotificationType notificationType) {
            this.notificationType = notificationType;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Notification build() {
            return new Notification(this);
        }
    }
}

interface NotificationGateway {
    public void send(Notification notification) throws Exception;
}

class EmailNotificationGateway implements NotificationGateway {
    @Override
    public void send(Notification notification) {
        String email = notification.getReceiver().getEmail()
                .orElseThrow(() -> new IllegalArgumentException("Email not found"));

        System.out.println("To: " + email);
        System.out.println("Content: " + notification.getContent());
        System.out.println("Subject: " + notification.getSubject());
    }
}

class SmsNotificationGateway implements NotificationGateway {
    @Override
    public void send(Notification notification) {
        String contact = notification.getReceiver().getContact()
                .orElseThrow(() -> new IllegalArgumentException("Contact not found"));

        System.out.println("To: " + contact);
        System.out.println("Content: " + notification.getContent());
    }
}

class NotificationRegistory {
    private final Map<NotificationType, NotificationGateway> gatewayMap;

    public NotificationRegistory() {
        gatewayMap = new HashMap<>();

        gatewayMap.put(NotificationType.EMAIL, new EmailNotificationGateway());
        gatewayMap.put(NotificationType.SMS, new SmsNotificationGateway());
    }

    public NotificationGateway getNotificationGateway(NotificationType notificationType) {
        return gatewayMap.get(notificationType);
    }
}

class RetriableNotificationGateway implements NotificationGateway {
    private final NotificationGateway notificationGateway;
    private final Integer maxRetry;
    private final Long waitTimeInMillis;

    public RetriableNotificationGateway(NotificationGateway notificationGateway, Integer maxRetry,
            Long waitTimeInMillis) {
        this.notificationGateway = notificationGateway;
        this.maxRetry = maxRetry;
        this.waitTimeInMillis = waitTimeInMillis;
    }

    @Override
    public void send(Notification notification) throws Exception {
        int attempt = 0;
        while (attempt < maxRetry) {
            try {
                notificationGateway.send(notification);
                notification.setNotificationStatus(NotificationStatus.DELIVERED);
                return;
            } catch (Exception e) {
                attempt++;
                if (attempt < maxRetry)
                    System.out.println("Notification delivery failed. Retrying again in some time");
                else {
                    notification.setNotificationStatus(NotificationStatus.FAILED);
                    throw new Exception("Failed to send notification even after " + maxRetry + " retries");
                }
                Thread.sleep(waitTimeInMillis * attempt);
            }
        }
    }
}

class NotificationService {
    private final ExecutorService executorService;
    private final NotificationRegistory notificationRegistory = new NotificationRegistory();

    public NotificationService(int corePoolSize, int maxPoolSize) {
        this.executorService = new ThreadPoolExecutor(corePoolSize, maxPoolSize, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1000), new ThreadPoolExecutor.CallerRunsPolicy());
    }

    public void sendNotification(Notification notification) {
        executorService.submit(() -> {
            NotificationGateway notificationGateway = new RetriableNotificationGateway(
                    notificationRegistory.getNotificationGateway(notification.getNotificationType()), 3, 1000L);
            try {
                notificationGateway.send(notification);
            } catch (Exception e) {
                System.out.println("Error while sending notification");
            }
        });
    }

    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS))
                executorService.shutdownNow();
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
