package Logger;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

enum LogLevel {
    DEBUG(1), INFO(2), WARN(3), ERROR(4);

    private final int priority;

    LogLevel(int priority) {
        this.priority = priority;
    }

    public int getPriority() {
        return priority;
    }
}

class LogMessage {
    private final LogLevel level;
    private final String message;
    private final LocalDateTime timestamp;
    private final String threadName;
    private final String callerClass;

    private LogMessage(Builder builder) {
        this.level = builder.level;
        this.message = builder.message;
        this.timestamp = builder.timestamp;
        this.threadName = builder.threadName;
        this.callerClass = builder.callerClass;
    }

    public LogLevel getLevel() {
        return level;
    }

    public String getMessage() {
        return message;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getThreadName() {
        return threadName;
    }

    public String getCallerClass() {
        return callerClass;
    }

    public static class Builder {
        private LogLevel level;
        private String message;
        private LocalDateTime timestamp;
        private String threadName;
        private String callerClass;

        public Builder level(LogLevel level) {
            this.level = level;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder timestamp(LocalDateTime ts) {
            this.timestamp = ts;
            return this;
        }

        public Builder threadName(String threadName) {
            this.threadName = threadName;
            return this;
        }

        public Builder callerClass(String callerClass) {
            this.callerClass = callerClass;
            return this;
        }

        public LogMessage build() {
            return new LogMessage(this);
        }
    }
}

interface LogFormatter {
    String format(LogMessage logMessage);
}

class SimpleFormatter implements LogFormatter {
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public String format(LogMessage logMessage) {
        return String.format("[%s] [%s] [%s] [%s] %s",
                logMessage.getTimestamp().format(DTF),
                logMessage.getLevel(),
                logMessage.getThreadName(),
                logMessage.getCallerClass(),
                logMessage.getMessage());
    }
}

class JSONFormatter implements LogFormatter {
    @Override
    public String format(LogMessage logMessage) {
        return String.format(
                "{\"timestamp\":\"%s\",\"level\":\"%s\",\"thread\":\"%s\",\"class\":\"%s\",\"message\":\"%s\"}",
                logMessage.getTimestamp(),
                logMessage.getLevel(),
                logMessage.getThreadName(),
                logMessage.getCallerClass(),
                logMessage.getMessage());
    }
}

interface LogAppender {
    void append(LogMessage logMessage);

    void setFormatter(LogFormatter formatter);
}

class ConsoleAppender implements LogAppender {
    private volatile LogFormatter formatter;

    public ConsoleAppender(LogFormatter formatter) {
        this.formatter = formatter;
    }

    @Override
    public void append(LogMessage logMessage) {
        System.out.println(formatter.format(logMessage));
    }

    @Override
    public void setFormatter(LogFormatter formatter) {
        this.formatter = formatter;
    }
}

class FileAppender implements LogAppender {
    private volatile LogFormatter formatter;
    private final PrintWriter writer;

    public FileAppender(String filePath, LogFormatter formatter) throws IOException {
        this.formatter = formatter;
        this.writer = new PrintWriter(new FileWriter(filePath, true /* append mode */));
    }

    @Override
    public void append(LogMessage logMessage) {
        writer.println(formatter.format(logMessage));
        writer.flush();
    }

    @Override
    public void setFormatter(LogFormatter formatter) {
        this.formatter = formatter;
    }

    public void close() {
        writer.close();
    }
}

class AsyncLogProcessor {
    private final ExecutorService executor;

    public AsyncLogProcessor() {
        this.executor = new ThreadPoolExecutor(
                1, 1,
                0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10_000),
                new ThreadPoolExecutor.DiscardPolicy());
    }

    public void process(LogMessage logMessage, List<LogAppender> appenders) {
        if (executor.isShutdown()) {
            System.err.println("[Logger] Shut down — message dropped: " + logMessage.getMessage());
            return;
        }
        List<LogAppender> snapshot = List.copyOf(appenders);
        executor.submit(() -> {
            for (LogAppender appender : snapshot)
                appender.append(logMessage);
        });
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

public class Logger {
    private static volatile Logger instance;

    private volatile LogLevel logLevel;
    private final List<LogAppender> appenders;
    private final AsyncLogProcessor asyncLogProcessor;

    private Logger() {
        logLevel = LogLevel.DEBUG;
        appenders = new CopyOnWriteArrayList<>();
        asyncLogProcessor = new AsyncLogProcessor();
    }

    public static Logger getInstance() {
        if (instance == null)
            synchronized (Logger.class) {
                if (instance == null)
                    instance = new Logger();
            }
        return instance;
    }

    public void shutdown() {
        asyncLogProcessor.shutdown();
    }

    public void setLogLevel(LogLevel logLevel) {
        this.logLevel = logLevel;
    }

    public void addAppender(LogAppender appender) {
        appenders.add(appender);
    }

    public void removeAppender(LogAppender appender) {
        appenders.remove(appender);
    }

    private void log(LogLevel level, String message) {
        if (level.getPriority() < logLevel.getPriority())
            return;

        LogMessage logMessage = new LogMessage.Builder()
                .level(level)
                .message(message)
                .timestamp(LocalDateTime.now())
                .threadName(Thread.currentThread().getName())
                .callerClass(Thread.currentThread().getStackTrace()[3].getClassName())
                .build();

        asyncLogProcessor.process(logMessage, appenders);
    }

    public void debug(String message) {
        log(LogLevel.DEBUG, message);
    }

    public void info(String message) {
        log(LogLevel.INFO, message);
    }

    public void warn(String message) {
        log(LogLevel.WARN, message);
    }

    public void error(String message) {
        log(LogLevel.ERROR, message);
    }
}
