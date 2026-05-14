# Notification System — Entities

---

## Enums
```
NotificationType:   EMAIL, SMS, PUSH_NOTIFICATION
NotificationStatus: PENDING, DELIVERED, FAILED
```

---

## Receiver
```
String id, name
Optional<String> email, contact, pushToken    // nullable at construction — use Optional.ofNullable

getters
```

---

## Notification                                         // Builder pattern
```
String id                         // UUID substring, generated at construction
Receiver receiver
NotificationType notificationType
String content, subject
NotificationStatus notificationStatus  // starts PENDING

setNotificationStatus(NotificationStatus)   // called by RetriableNotificationGateway on success/failure
getters
```

### Notification.Builder (inner static class)
```
Builder receiver(Receiver)
Builder notificationType(NotificationType)
Builder content(String)
Builder subject(String)
Notification build()
```

---

## interface NotificationGateway
```
void send(Notification notification) throws Exception
```

### EmailNotificationGateway implements NotificationGateway
- reads `receiver.getEmail()` — throws `IllegalArgumentException` if absent
- prints To / Content / Subject

### SmsNotificationGateway implements NotificationGateway
- reads `receiver.getContact()` — throws `IllegalArgumentException` if absent
- prints To / Content

---

## NotificationRegistry
```
Map<NotificationType, NotificationGateway> gatewayMap   // HashMap, wired at construction
    EMAIL → EmailNotificationGateway
    SMS   → SmsNotificationGateway

NotificationGateway getNotificationGateway(NotificationType)
```

---

## RetriableNotificationGateway implements NotificationGateway  // Decorator pattern
```
NotificationGateway notificationGateway     // wrapped gateway
Integer maxRetry
Long waitTimeInMillis

void send(Notification notification)
    // retry loop up to maxRetry attempts
    // on success: setNotificationStatus(DELIVERED), return
    // on each failure: sleep(waitTimeInMillis * attempt), then retry
    // after maxRetry failures: setNotificationStatus(FAILED), throw Exception
```
Note: backoff grows linearly — `waitTimeInMillis * attempt` (1x, 2x, 3x…).

---

## NotificationService
```
ExecutorService executorService     // ThreadPoolExecutor(corePoolSize, maxPoolSize,
                                    //   60s keepAlive, ArrayBlockingQueue(1000),
                                    //   CallerRunsPolicy)
NotificationRegistry notificationRegistory

void sendNotification(Notification notification)
    // submit async task to executor:
    //   wrap gateway in RetriableNotificationGateway(maxRetry=3, waitTime=1000ms)
    //   → gateway.send(notification)

void shutdown()
```

---

## Key Concurrency Notes
| Scenario | Solution |
|---|---|
| Async notification dispatch | `ExecutorService.submit()` — each notification processed in a thread pool worker |
| Queue overflow / backpressure | `CallerRunsPolicy` — caller thread sends notification if pool + queue are full |
| Bounded work queue | `ArrayBlockingQueue(1000)` — prevents unbounded memory growth under load |
| Transient gateway failures | `RetriableNotificationGateway` — up to 3 retries with linear backoff |

---

## Flow Summary
- **Send:** NotificationService.sendNotification → submit to thread pool → worker picks up → RetriableNotificationGateway.send → NotificationRegistry.getGateway(type) → gateway.send → set DELIVERED
- **Retry on failure:** gateway.send throws → sleep(waitTime * attempt) → retry → after maxRetry: set FAILED + throw
- **Backpressure:** pool full + queue full → CallerRunsPolicy → caller thread executes send inline
- **Shutdown:** NotificationService.shutdown() → graceful executor shutdown