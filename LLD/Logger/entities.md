# Logger — Entities

---

## Enums
```
LogLevel: DEBUG(1), INFO(2), WARN(3), ERROR(4)
    priority: int — used to compare levels; only messages >= configured level are logged
```

---

## LogMessage                                      // Builder pattern — immutable
```
LogLevel level
String message
LocalDateTime timestamp
String threadName
String callerClass          // extracted from Thread.currentThread().getStackTrace()[3]

getters (no setters — fully immutable after build)
```

### LogMessage.Builder
```
Builder level(LogLevel)
Builder message(String)
Builder timestamp(LocalDateTime)
Builder threadName(String)
Builder callerClass(String)
LogMessage build()
```

---

## interface LogFormatter                          // Strategy — how to format
```
String format(LogMessage logMessage)
```

### SimpleFormatter
- `[yyyy-MM-dd HH:mm:ss] [LEVEL] [thread] [class] message`

### JSONFormatter
- `{"timestamp":"...","level":"...","thread":"...","class":"...","message":"..."}`

---

## interface LogAppender                           // Strategy — where to write
```
void append(LogMessage logMessage)
void setFormatter(LogFormatter formatter)
```

### ConsoleAppender
```
volatile LogFormatter formatter
```
- `append`: `System.out.println(formatter.format(logMessage))`

### FileAppender
```
volatile LogFormatter formatter
PrintWriter writer          // opened once in constructor (append mode = true)
ReentrantLock lock          // defensive; in async mode dispatch is single-threaded
```
- `append`: acquires lock → write + flush → release
- `close()`: closes the underlying PrintWriter

---

## Logger                                          // Singleton + async dispatch
```
volatile LogLevel logLevel                         // global threshold; default DEBUG
CopyOnWriteArrayList<LogAppender> appenders

LinkedBlockingQueue<LogMessage> logQueue           // bounded; QUEUE_CAPACITY = 10_000
Thread dispatchThread                              // single daemon thread "log-dispatch-thread"
AtomicBoolean running                              // controls dispatch loop lifecycle

static getInstance()                              // double-checked locking + volatile instance

void setLogLevel(LogLevel)
void addAppender(LogAppender)
void removeAppender(LogAppender)

void debug/info/warn/error(String message)
    → delegate to log(level, message)

private void log(LogLevel, String)
    → level check: if level.priority < logLevel.priority → return
    → build LogMessage (timestamp, thread, callerClass from stack trace)
    → logQueue.offer(logMessage)   // non-blocking; drops + stderr warn if queue full

private void dispatchLoop()        // runs on dispatchThread
    → while (running || !queue.isEmpty())
        → logQueue.poll(100ms timeout)   // re-checks running every 100ms
        → fan out to all appenders

void shutdown()
    → running.set(false)
    → dispatchThread.join()        // blocks until all queued messages are flushed
```

---

## Async Flow
```
Application thread          log-dispatch-thread
─────────────────           ───────────────────
logger.info("msg")
  level check ✓
  build LogMessage
  queue.offer(msg) ──────► dispatchLoop polls msg
  returns immediately        appender.append(msg)   ← I/O happens here
                             appender.append(msg)   ← all appenders
```

---

## Patterns Used
| Pattern | Where |
|---|---|
| Singleton | Logger — single global instance |
| Builder | LogMessage — immutable construction |
| Strategy | LogFormatter — pluggable format (Simple / JSON) |
| Strategy | LogAppender — pluggable sink (Console / File / ...) |
| Producer-Consumer | Logger (producer) + dispatchThread (consumer) via BlockingQueue |

---

## Key Concurrency Notes
| Scenario | Solution |
|---|---|
| Singleton creation race | `volatile instance` + double-checked locking |
| Level/formatter swap at runtime | `volatile logLevel`, `volatile formatter` — visibility across threads |
| Adding/removing appenders concurrently | `CopyOnWriteArrayList` — safe iteration + mutation |
| Queue hand-off between threads | `LinkedBlockingQueue` — thread-safe by design |
| Queue full (back-pressure) | `offer()` drops message + logs to stderr (non-blocking) |
| Graceful shutdown | `running.set(false)` + `dispatchThread.join()` — drains all pending messages |
| FileAppender concurrent writes | `ReentrantLock` — defensive; in async mode only dispatch thread calls append |

---

## Flow Summary
- **Setup:** `Logger.getInstance()` → `addAppender(...)` → `setLogLevel(INFO)` → constructor starts `log-dispatch-thread`
- **Log call:** `logger.info("msg")` → level check → build `LogMessage` → `queue.offer()` → returns immediately
- **Dispatch:** background thread polls queue → fans out to all appenders → I/O happens off the calling thread
- **Shutdown:** `logger.shutdown()` → sets `running=false` → joins dispatch thread → all queued messages flushed before return
- **Queue full:** message dropped, warning on stderr — no blocking of application thread
