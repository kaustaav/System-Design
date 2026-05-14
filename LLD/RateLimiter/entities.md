# Rate Limiter — Entities

---

## interface RateLimitingStrategy                       // Strategy pattern
```
boolean shouldAllowRequests(String apiToken, int maxRequest)
```

---

## TokenBucketRateLimitingStrategy implements RateLimitingStrategy
```
long windowSizeInMillis
Map<String, TokenBucket> requestMap     // ConcurrentHashMap, keyed by apiToken

boolean shouldAllowRequests(String apiToken, int maxRequest)
    // putIfAbsent new TokenBucket(currentTime, maxRequest)
    // synchronized(tokenBucket): refill → if tokensLeft > 0: consume + true, else false
```

### TokenBucket (inner class)
```
int maxCapacity
int tokensLeft
long lastRefilledTime

void refill(long currentTime)
    // tokensToAdd = maxCapacity * (elapsed / windowSizeInMillis)
    // if tokensToAdd > 0: tokensLeft = min(tokensLeft + tokensToAdd, maxCapacity)
    //                     lastRefilledTime = currentTime
```
Note: gradual proportional refill — tokens trickle back over the window, not all at once.

---

## FixedWindowRateLimitingStrategy implements RateLimitingStrategy
```
long windowSizeInMillis
Map<String, FixedWindow> requestMap     // ConcurrentHashMap, keyed by apiToken

boolean shouldAllowRequests(String apiToken, int maxRequest)
    // putIfAbsent new FixedWindow(currentTime)
    // synchronized(fixedWindow): if window expired → reset; if counter >= maxRequest: false, else counter++ + true
```

### FixedWindow (inner class)
```
int counter
long currentWindow                      // epoch ms of window start

void reset(long windowStart)            // counter = 0, currentWindow = windowStart
```
Note: hard reset at window boundary — burst at start of each window is permitted.

---

## RateLimiter                                          // Singleton pattern
```
RateLimitingStrategy rateLimitingStrategy    // default: FixedWindowRateLimitingStrategy(60000ms)
Map<String, Integer> premiumUsersMap         // ConcurrentHashMap — apiToken → custom maxRequest limit
int maxRequest = 100                         // default limit for non-premium tokens

static getInstance()                         // double-checked locking

void handleRequest(String apiToken)
    // resolve limit: premiumUsersMap.getOrDefault(apiToken, this.maxRequest)
    // strategy.shouldAllowRequests(apiToken, limit) → print "Request accepted" / "Request denied"

void setRateLimitingStrategy(RateLimitingStrategy)   // injectable — swap algorithm at runtime
```

---

## Key Concurrency Notes
| Scenario | Solution |
|---|---|
| Concurrent requests for the same token | `synchronized(tokenBucket)` / `synchronized(fixedWindow)` — fine-grained per-token lock |
| First request for a new token | `ConcurrentHashMap.putIfAbsent` — atomic initialization of per-token state |
| Singleton initialization | `volatile instance` + double-checked locking |
| Premium user map | `ConcurrentHashMap` — safe concurrent reads and updates |

---

## Strategy Comparison
| Property | Token Bucket | Fixed Window |
|---|---|---|
| Refill | Gradual — proportional to elapsed time | Hard reset at window boundary |
| Burst handling | Smooths bursts — tokens trickle back | Allows full burst at window start |
| Edge case | Spike at window end + start (burst of 2x) | None — burst capped within window |
| State per token | maxCapacity, tokensLeft, lastRefilledTime | counter, currentWindow |

---

## Flow Summary
- **Request:** RateLimiter.handleRequest → lookup per-token limit (premiumUsersMap) or default 100 → strategy.shouldAllowRequests (synchronized on bucket/window) → allow or deny
- **Token bucket refill:** proportional tokens restored based on elapsed time since last refill
- **Fixed window reset:** counter zeroed when `currentTime - currentWindow >= windowSizeInMillis`
- **Premium user:** higher maxRequest stored in premiumUsersMap; looked up per apiToken before delegating to strategy
- **Strategy swap:** RateLimiter.setRateLimitingStrategy — live swap without restarting