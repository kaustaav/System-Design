package RateLimiter;

import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class main {
}

interface RateLimitingStrategy {
    public boolean shouldAllowRequests(String apiToken, int maxRequest);
}

class TokenBucketRateLimitingStrategy implements RateLimitingStrategy {
    private final long windowSizeInMillis;
    private final Map<String, TokenBucket> requestMap;

    public TokenBucketRateLimitingStrategy(long windowSizeInMillis) {
        this.windowSizeInMillis = windowSizeInMillis;
        this.requestMap = new ConcurrentHashMap<>();
    }

    @Override
    public boolean shouldAllowRequests(String apiToken, int maxRequest) {
        long currentTime = System.currentTimeMillis();
        TokenBucket tokenBucket = requestMap.computeIfAbsent(
                apiToken,
                k -> new TokenBucket(currentTime, maxRequest));
        synchronized (tokenBucket) {
            tokenBucket.refill(currentTime);
            if (tokenBucket.tokensLeft > 0) {
                tokenBucket.tokensLeft--;
                return true;
            }
            return false;
        }
    }

    class TokenBucket {
        public int maxCapacity;
        public int tokensLeft;
        public long lastRefilledTime;

        public TokenBucket(long lastRefilledTime, int maxRequest) {
            this.tokensLeft = maxRequest;
            this.maxCapacity = maxRequest;
            this.lastRefilledTime = lastRefilledTime;
        }

        public void refill(long currentTime) {
            long elapsed = currentTime - lastRefilledTime;
            long tokensToAdd = (maxCapacity * elapsed) / windowSizeInMillis;
            if (tokensToAdd > 0) {
                this.tokensLeft = (int) Math.min(
                        this.tokensLeft + tokensToAdd,
                        maxCapacity);
                this.lastRefilledTime = currentTime;
            }
        }
    }
}

class FixedWindowRateLimitingStrategy implements RateLimitingStrategy {
    private final long windowSizeInMillis;
    private final Map<String, FixedWindow> requestMap;

    public FixedWindowRateLimitingStrategy(long windowSizeInMillis) {
        this.windowSizeInMillis = windowSizeInMillis;
        this.requestMap = new ConcurrentHashMap<>();
    }

    @Override
    public boolean shouldAllowRequests(String apiToken, int maxRequest) {
        long currentTime = System.currentTimeMillis();
        FixedWindow fixedWindow = requestMap.computeIfAbsent(
                apiToken,
                k -> new FixedWindow(currentTime));
        synchronized (fixedWindow) {
            long currentWindow = (currentTime / windowSizeInMillis) * windowSizeInMillis;
            if (currentWindow > fixedWindow.currentWindow) {
                fixedWindow.reset(currentWindow);
            }
            if (fixedWindow.counter >= maxRequest)
                return false;
            fixedWindow.counter++;
            return true;
        }
    }

    class FixedWindow {
        public int counter;
        public long currentWindow;

        public FixedWindow(long currentTime) {
            this.currentWindow = (currentTime / windowSizeInMillis) * windowSizeInMillis;
            this.counter = 0;
        }

        public void reset(long windowStart) {
            this.currentWindow = windowStart;
            this.counter = 0;
        }
    }
}

class SlidingWindowLogRateLimitingStrategy implements RateLimitingStrategy {
    private final long windowSizeInMillis;
    private final Map<String, LinkedList<Long>> requestMap;

    public SlidingWindowLogRateLimitingStrategy(long windowSizeInMillis) {
        this.windowSizeInMillis = windowSizeInMillis;
        this.requestMap = new ConcurrentHashMap<>();
    }

    @Override
    public boolean shouldAllowRequests(String apiToken, int maxRequest) {
        long currentTime = System.currentTimeMillis();
        LinkedList<Long> timestamps = requestMap.computeIfAbsent(apiToken, k -> new LinkedList<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && currentTime - timestamps.peekFirst() >= windowSizeInMillis)
                timestamps.pollFirst();
            if (timestamps.size() >= maxRequest)
                return false;
            timestamps.addLast(currentTime);
            return true;
        }
    }
}

class SlidingWindowCounterRateLimitingStrategy implements RateLimitingStrategy {
    private final long windowSizeInMillis;
    private final Map<String, SlidingWindowCounter> requestMap;

    public SlidingWindowCounterRateLimitingStrategy(long windowSizeInMillis) {
        this.windowSizeInMillis = windowSizeInMillis;
        this.requestMap = new ConcurrentHashMap<>();
    }

    @Override
    public boolean shouldAllowRequests(String apiToken, int maxRequest) {
        long currentTime = System.currentTimeMillis();
        SlidingWindowCounter counter = requestMap.computeIfAbsent(apiToken,
                k -> new SlidingWindowCounter(currentTime));
        synchronized (counter) {
            counter.updateWindow(currentTime);
            double elapsed = (double) (currentTime - counter.currentWindowStart) / windowSizeInMillis;
            double estimatedCount = counter.currentCount + counter.previousCount * (1 - elapsed);
            if (estimatedCount >= maxRequest)
                return false;
            counter.currentCount++;
            return true;
        }
    }

    class SlidingWindowCounter {
        public long currentWindowStart;
        public int currentCount;
        public int previousCount;

        public SlidingWindowCounter(long currentTime) {
            this.currentWindowStart = (currentTime / windowSizeInMillis) * windowSizeInMillis;
            this.currentCount = 0;
            this.previousCount = 0;
        }

        public void updateWindow(long currentTime) {
            long newWindowStart = (currentTime / windowSizeInMillis) * windowSizeInMillis;
            if (newWindowStart > currentWindowStart) {
                previousCount = currentCount;
                currentCount = 0;
                currentWindowStart = newWindowStart;
            }
        }
    }
}

class RateLimiter {
    private static volatile RateLimiter instance;
    private RateLimitingStrategy rateLimitingStrategy;
    private final Map<String, Integer> premiumUsersMap;
    private int maxRequest = 100;

    private RateLimiter() {
        this.rateLimitingStrategy = new FixedWindowRateLimitingStrategy(60000L);
        this.premiumUsersMap = new ConcurrentHashMap<>();
    }

    public static RateLimiter getInstance() {
        if (instance == null)
            synchronized (RateLimiter.class) {
                if (instance == null)
                    instance = new RateLimiter();
            }
        return instance;
    }

    public void handleRequest(String apiToken) {
        boolean shouldAllow = rateLimitingStrategy.shouldAllowRequests(apiToken,
                premiumUsersMap.getOrDefault(apiToken, this.maxRequest));
        if (shouldAllow)
            System.out.println("Request accepted");
        else
            System.out.println("Request denied");
    }

    public void setRateLimitingStrategy(RateLimitingStrategy rateLimitingStrategy) {
        this.rateLimitingStrategy = rateLimitingStrategy;
    }
}
