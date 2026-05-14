package RateLimiter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
        requestMap.putIfAbsent(apiToken, new TokenBucket(currentTime, maxRequest));
        TokenBucket tokenBucket = requestMap.get(apiToken);
        synchronized (tokenBucket) {
            tokenBucket.refill(currentTime);
            if(tokenBucket.tokensLeft > 0) {
                tokenBucket.tokensLeft--;
                return true;
            }
            else
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
            int tokensToAdd = (int) (maxCapacity * (currentTime - lastRefilledTime) / windowSizeInMillis);
            if(tokensToAdd > 0) {
                this.tokensLeft = Math.min(this.tokensLeft + tokensToAdd, maxCapacity);
                this.lastRefilledTime = currentTime;
            }
        }
    }
}

class FixedWindowRateLimitingStrategy implements  RateLimitingStrategy {
    private final long windowSizeInMillis;
    private final Map<String, FixedWindow> requestMap;

    public FixedWindowRateLimitingStrategy(long windowSizeInMillis) {
        this.windowSizeInMillis = windowSizeInMillis;
        this.requestMap = new ConcurrentHashMap<>();
    }

    @Override
    public boolean shouldAllowRequests(String apiToken, int maxRequest) {
        long currentTime = System.currentTimeMillis();
        requestMap.putIfAbsent(apiToken, new FixedWindow(currentTime));
        FixedWindow fixedWindow = requestMap.get(apiToken);
        synchronized (fixedWindow) {
            if(currentTime - fixedWindow.currentWindow >= windowSizeInMillis)
                fixedWindow.reset(currentTime);
            if(fixedWindow.counter >= maxRequest)
                return false;
            else {
                fixedWindow.counter++;
                return true;
            }
        }
    }

    class FixedWindow {
        public int counter;
        public long currentWindow;

        public FixedWindow(long currentWindow) {
            this.counter = 0;
            this.currentWindow = currentWindow;
        }

        public void reset(long windowStart) {
            this.currentWindow = windowStart;
            this.counter = 0;
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
        if(instance == null)
            synchronized (RateLimiter.class) {
                if(instance == null)
                    instance = new RateLimiter();
            }
        return instance;
    }

    public void handleRequest(String apiToken) {
        boolean shouldAllow = rateLimitingStrategy.shouldAllowRequests(apiToken, premiumUsersMap.getOrDefault(apiToken, this.maxRequest));
        if(shouldAllow)
            System.out.println("Request accepted");
        else
            System.out.println("Request denied");
    }

    public void setRateLimitingStrategy(RateLimitingStrategy rateLimitingStrategy) {
        this.rateLimitingStrategy = rateLimitingStrategy;
    }
}
