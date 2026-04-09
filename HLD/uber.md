# Uber System Design — Complete HLD Deep Dive

> **Target Audience:** Engineers preparing for L4/L5 system design interviews at Google, Meta, Uber  
> **Scope:** End-to-end Uber ride-hailing system — booking, matchmaking, live location, payments, notifications  
> **Difficulty:** Senior / Staff Engineer level

---

## Table of Contents
1. [Functional Requirements](#functional-requirements)
2. [Non-Functional Requirements](#non-functional-requirements)
3. [Back-of-Envelope Estimation](#back-of-envelope-estimation)
4. [High-Level Architecture](#high-level-architecture)
5. [Core Workflows](#core-workflows)
6. [Key Design Decisions](#key-design-decisions)
7. [Interview Q&A — Deep Dives](#interview-qa--deep-dives)
8. [Important Keywords & Concepts](#important-keywords--concepts)
9. [Quick Reference NFR Block](#quick-reference-nfr-block)
10. [Concepts Checklist](#concepts-checklist)

---

## Functional Requirements

1. Users can book rides, get ETA and fare estimates
2. Drivers receive booking requests based on matchmaking
3. Drivers can accept or decline bookings
4. Live location of driver is shown to rider along with route

---

## Non-Functional Requirements

| # | Requirement | Target |
|---|---|---|
| 1 | High Availability | 99.99% uptime |
| 2 | Booking reliability | No bookings should be lost |
| 3 | Low latency | Matchmaking < 2s, location updates < 5s |
| 4 | Scalability | Millions of concurrent users and drivers globally |
| 5 | Consistency | Eventual consistency acceptable for location; strong for ride state |

---

## Back-of-Envelope Estimation

| Metric | Estimate |
|---|---|
| DAU Riders | 10M |
| DAU Drivers | 1M |
| Avg rides / day | 10M |
| Avg new rides / second | 10M / 10^5 = **100 rides/sec** |
| Peak new rides / second | **500 rides/sec** |
| Peak active drivers | **100K** |
| Driver location update frequency | Every 5 seconds |
| Location update QPS | 1M drivers × (1/5) = **200K QPS** |
| Peak location QPS | **~20K QPS** (with sensor-based delta updates) |
| Rider DB size | 10M × 1KB = **10GB** — Postgres |
| Driver DB size | 1M × 1KB = **1GB** — Postgres |
| Ride DB size | 10M/day × 365 × 0.1KB = **3.6TB** — DynamoDB |
| Driver Location DB | 1M/5 × 30 × 0.1KB = **60TB** — DynamoDB |

---

## High-Level Architecture

```
                        ┌─────────────┐     ┌─────────────┐
                        │   Rider DB   │     │  Driver DB   │
                        │  (Postgres)  │     │  (Postgres)  │
                        └──────┬───────┘     └──────┬───────┘
                               │                    │
                        ┌──────▼────────────────────▼───────┐
                        │         User Management Service    │
                        └──────────────────┬────────────────┘
                                           │
┌──────────┐    ┌────────────────────────────────────────────────┐
│  Rider   │    │                   API Gateway                   │
│(iOS/And) ├───►│     Load Balancer | Rate Limit | Auth           │
└──────────┘    └───────────────────────┬────────────────────────┘
                                        │
┌──────────┐                   ┌────────▼─────────┐
│  Driver  │◄──────WebSocket───┤  Ride Management  ├──► Ride DB (Postgres)
│(iOS/And) ├──────WebSocket───►│     Service       │
└──────────┘                   └────────┬──────────┘
      │                                 │
      │ Location                   ┌────▼──────────────┐
      │ every 5s                   │ Matchmaking Service│
      │                            │ (Redis Lua + H3)   │
      ▼                            └────────┬───────────┘
┌─────────────────┐                         │
│ Location Update │──► Kafka ──► Location   │
│    Service      │   (by      Update Worker│
└─────────────────┘  driverId) └────────────┘
                                      │
                          ┌───────────▼──────────────┐
                          │     Location Cache        │
                          │  (Redis — H3 geo index)   │
                          │  Partitioned by Geography │
                          └───────────┬──────────────┘
                                      │
                          ┌───────────▼──────────────┐
                          │    Location DB            │
                          │    (DynamoDB)             │
                          │  Partition: driverId      │
                          │  Sort: timestamp          │
                          └──────────────────────────┘

Supporting Services:
├── ETA & Location Service (3rd Party — Google Maps + circuit breaker)
├── Fare Estimation Service (ML models + Google Maps fallback)
├── Payment Service (Stripe — async, circuit breaker)
├── Notification Service (APN + FCM + idempotent keys)
└── Analytical OLAP (Snowflake — batch aggregation)
```

### Component Responsibilities

| Component | Responsibility |
|---|---|
| GeoDNS | Routes users to nearest healthy regional cluster |
| API Gateway | Load balancing, rate limiting, authentication |
| Ride Management | Owns ride lifecycle state machine |
| Matchmaking Service | Finds best available driver for a ride request |
| Location Update Service | Ingests driver GPS, stamps server timestamp, publishes to Kafka |
| Location Cache (Redis) | Serves live driver locations for H3 geo-index |
| H3 Index | Hierarchical geo-partition for spatial driver lookup |
| Kafka | Event bus — separate topics for location and ride events |
| Notification Service | Delivers ride requests/updates to drivers via APN/FCM |
| Payment Service | Async payment processing via Stripe |

---

## Core Workflows

### 1. Ride Booking Flow
1. Rider opens app → API GW authenticates → Ride Management Service creates ride in `INITIATED` state
2. Admission Control FIFO Queue buffers request during peak load
3. Matchmaking Service queries H3 Redis index for nearby available drivers
4. Top-3 drivers sent ride request in parallel via Notification Service
5. First driver to accept → Redis Lua script atomically acquires lease → ride moves to `DRIVER_ASSIGNED`
6. WebSocket pushes driver location to rider in real time
7. Ride moves through `STARTED` → `COMPLETED`
8. Payment triggered asynchronously

### 2. Driver Location Update Flow
1. Driver app sends location every 5 seconds (sensor-based delta — only on meaningful movement)
2. Location Update Service receives event, **stamps server-side timestamp**
3. Event published to Kafka — **partitioned by driverId**
4. Location Update Worker consumes event, updates Redis H3 index (if timestamp is newer)
5. Async write to DynamoDB for persistence (1 day hot, then archive to Snowflake)
6. Heartbeat ping-pong sent separately — directly to Redis with TTL (bypasses Kafka entirely)

### 3. Matchmaking Flow
1. Ride request arrives → Matchmaking Service reads H3 Redis index
2. Start at precomputed H3 resolution for that area
3. If drivers < threshold → expand to 6 neighbouring cells (`kRing(1)`) → `kRing(2)` etc.
4. Rank candidates by distance, rating, acceptance rate
5. Batch top-3 in parallel with 5-10 second timeout
6. On accept → Lua script atomically sets `DRIVER_ASSIGNED`, others rejected
7. On all reject/timeout → next batch of 3, previous drivers deprioritized

---

## Key Design Decisions

### DB Choices

| Data | DB | Reasoning |
|---|---|---|
| Riders, Drivers | Postgres | Relational, read-heavy, low write volume |
| Rides | Postgres (regional partitions) | Complex queries, relational joins for analytics/payment |
| Active Rides | Redis SET per region | O(1) lookup, fast matchmaking set subtraction |
| Driver Location (hot) | DynamoDB | High write throughput, 1 day retention |
| Driver Location (cold) | Snowflake | Analytics, ML feature store |
| Live Driver Location | Redis (H3) | Geo-indexed, in-memory, serving source of truth |

### Kafka Topic Design

| Topic | Partition Key | Reason |
|---|---|---|
| `driver.location.updates` | `driverId` | Drivers exist without rides; ordered per driver |
| `ride.state.changes` | `rideId` | All state transitions for one ride must be ordered |

> **Why not cellId?** Geographic skewness — dense cities create hot partitions. Uniform distribution requires driverId/rideId.

### Redis Architecture

| Store | Purpose | Key Design |
|---|---|---|
| Driver availability | Heartbeat TTL | `driver:{driverId}:online` |
| H3 geo-index | Live driver location per cell | `h3:{cellId}:drivers` |
| Active rides | Fast matchmaking subtraction | `region:{regionId}:active_rides` |
| Ride lease lock | Prevent double assignment | `ride:{rideId}:lock` |
| Idempotent event keys | Exactly-once processing | `event:{eventId}:processed` |

---

## Interview Q&A — Deep Dives

### Q1 — Matchmaking Algorithm

**Q: Push vs pull model? Retry on reject? Timeout strategy?**

**A:**
Use a **hybrid model**:
- **Push** to available idle drivers when ride request comes in
- **Pull** when driver is 70-80% through existing ride — they start polling for next ride proactively (good for business — reduces idle time between rides)

**Retry strategy:**
- Batch top-3 drivers in **parallel** — not sequential
- Sequential means worst case 3 × 10s = 30s wait for rider. Unacceptable.
- Parallel batching: one 10-second window regardless of batch size
- Rejected/non-responding drivers deprioritized, retried later if no one else accepts

**Concurrency — Preventing double assignment:**
- Lease-based lock in Redis
- **Lua scripts** make lock acquisition atomic — check + set in single operation, no interleaving
- First acceptance wins, all subsequent attempts rejected
- Redis quorum=3 — lock replicated across 3 nodes, single node crash doesn't lose lock

> **Follow-up: What if service instance holding lock crashes mid-match?**
>
> Redis quorum=3 ensures lock state survives single node failure. Lock also has TTL — auto-expires and next match attempt proceeds cleanly. No deadlocks possible.

---

### Q2 — Location Update Pipeline

**Q: Redis vs DynamoDB source of truth? Kafka scaling? Heartbeat?**

**A:**

**Availability Signal (Heartbeat) — separate from location:**
- Lightweight ping-pong directly to Redis — **bypasses Kafka entirely**
- Redis key per driver with TTL — key expiry = driver offline
- Zero battery drain, zero Kafka overhead, self-expiring
- WhatsApp online status is the exact analogy

**Location Freshness — sensor-based delta updates:**
- Don't send location every 5s blindly — use client-side sensors
- Only emit when speed/direction changes beyond threshold
- Drivers stuck in traffic or resting → no events, just heartbeat
- Reduces 200K QPS significantly during low-movement periods

**Out-of-order prevention:**
- Location Update Service stamps **server-side timestamp** at ingestion
- Client timestamps untrustworthy — GPS drift, battery saver, manipulated clocks
- Redis conditional write — only update if `incoming_timestamp > stored_timestamp`

**Persistence layering:**
- Redis = serving source of truth (H3 index, matchmaking)
- DynamoDB = real source of truth (1 day hot storage)
- Archive to Snowflake for ML/analytics
- H3 falls back to DynamoDB connector if Redis goes down (circuit breaker)

**Kafka scaling:**
- Over-provision partitions at creation time using back-of-envelope numbers
- **Never add partitions dynamically** — causes rebalancing pause at worst possible moment
- Scale via consumer group workers, not partition count

> **Follow-up: Correlated failure — Redis stress and Kafka lag spike simultaneously at peak?**
>
> Monitor Kafka consumer lag continuously. Prioritize location writes during high lag. Redis cluster with AOF persistence reduces crash probability. If Redis does go down — H3 falls back to DynamoDB. At peak traffic speeds in dense cities, drivers move slowly — H3 cell change in 30-60 second window is unlikely. Acceptable degradation.

---

### Q3 — FIFO Admission Control

**Q: Why FIFO? What are you protecting against? Rejection strategy?**

**A:**
FIFO because **earlier ride requests deserve priority** — fairness guarantee. Standard queue doesn't provide ordering.

Protecting against: **thundering herd during peak load** — sudden spike overwhelming Ride Management Service before autoscaling kicks in.

**Strategy:**
- Over-estimate queue depth to bridge 30-60 second pod startup window
- Predictive autoscaling for known peaks (Monday mornings, office hours, events)
- Kubernetes HPA scales consumer pods, not Kafka partitions

**For unpredictable spikes (concert ending):**
- Queue hits max depth → drop requests → return retryable 503 to client
- Client retries with **exponential backoff + jitter** — staggers retries, prevents retry thundering herd
- New pods come up, queue drains, system self-heals

> **Follow-up: Over-estimation reduces probability but doesn't eliminate overflow. Truly unpredictable spikes?**
>
> No system guarantees zero drops under infinite load. Goal is graceful degradation — drop cleanly with retryable errors rather than crashing. Jitter ensures retry storm doesn't recreate original spike.

---

### Q4 — Ride DB Design

**Q: DB choice? Index design for city-wide queries?**

**A:**
**Postgres over DynamoDB** for rides:
- Read-heavy, relational, needs complex queries for analytics and payment reconciliation
- 500 peak writes/sec globally — trivially manageable for Postgres
- Partition regionally — Bengaluru queries never touch New York partition

**Index design:**
```sql
-- Composite index for city-wide queries
CREATE INDEX idx_rides_region_status_created 
ON rides (region, status, created_at);

-- Partial index — only ACTIVE rides (avoids churn from completed rides)
CREATE INDEX idx_rides_active 
ON rides (region, created_at) 
WHERE status = 'ACTIVE';
```

**Active rides for matchmaking — don't query Postgres:**
- Redis SET per region holds active rideIds — O(1) lookup
- H3 index has available drivers in memory
- Available drivers = H3 cell drivers SET MINUS active ride drivers SET
- Redis set subtraction at query time
- When ride → `DRIVER_ASSIGNED`: remove driver from available set in Redis
- Postgres = source of truth for completed rides, analytics, reconciliation only

> **Follow-up: Concert spike — cache empty, thousands hit DB simultaneously?**
>
> H3 and Redis are continuously pre-populated via location pipeline. No cold cache for matchmaking — available driver set is always warm. Ride DB is not in the matchmaking hot path at all.

---

### Q5 — Fare Estimation & Circuit Breaker

**Q: Google Maps goes down. Fallback? SLA under flaky dependency?**

**A:**

**Detection — Circuit Breaker (not periodic healthcheck):**
- Periodic healthcheck has up to 30-second detection window — too slow
- First failed live request triggers circuit breaker immediately
- Service context switches to fallback mode — no requests wasted on dead dependency

**Fallback:**
- ML models trained on historical ride data in Snowflake
- Models capture time-of-day traffic patterns per H3 cell (Monday 9AM Bengaluru ≠ Sunday 3PM)
- Not static ETAs — statistical models, reasonably accurate for normal conditions
- OpenStreetMap as alternative routing provider for navigation
- Communicate uncertainty to user: "Estimated time may vary"

**Recovery — Circuit Breaker States:**
```
CLOSED (normal) 
    → [failures exceed threshold] → 
OPEN (fallback)
    → [periodic healthcheck succeeds] → 
HALF-OPEN (probe)
    → [live traffic succeeds] → 
CLOSED (normal)
```

> **Follow-up: Models stale for unusual events (accidents, road closures)?**
>
> Accuracy degrades for abnormal events — this is communicated to the user. The fallback is best-effort, not guaranteed. For a brief Google Maps outage, models are accurate enough to book rides. For extended outages, OpenStreetMap provides live routing.

---

### Q6 — Payment Service

**Q: Sync or async? Stripe timeout? State machine edge cases?**

**A:**
Payment must **never block ride completion** — always async.

**Flow:**
1. Ride reaches `COMPLETED` → publishes event to payment Kafka topic
2. Payment Service consumes asynchronously
3. Initiates with Stripe → state moves to `PAYMENT_STARTED`
4. Success → `PAYMENT_COMPLETED`
5. Driver marks trip complete OR rider initiates manually

**State Machine:**
```
NOT_READY → PAYMENT_STARTED → PAYMENT_COMPLETED
                    ↓ (on failure)
             PAYMENT_FAILED → PAYMENT_STARTED (retry)
                    ↓ (rider cancels)
            PAYMENT_CANCELLED
```

**Stripe Timeout / Failure:**
- Circuit breaker on Stripe — same pattern as Google Maps
- Failed events → **Dead Letter Queue** → retry with exponential backoff
- After N retries → `PAYMENT_FAILED` state, alert rider and driver
- Rider retries payment manually from app

**Idempotency:**
- Each payment attempt carries idempotent key sent to Stripe
- Stripe deduplicates — prevents double charging even on retry

---

### Q7 — Notification Service

**Q: APN/FCM failure? Exactly-once delivery? Interaction with ride timeout?**

**A:**
APN and FCM are **at-least-once** by nature. Achieve exactly-once at application layer:

```
Driver receives notification
    → Check Redis: has eventId been processed?
    → YES: drop silently
    → NO: process + mark eventId as seen in Redis (with TTL)
```

**Timeout + Retry:**
- 5-10 second acceptance window per driver
- No response → Redis lease expires → matchmaking retries next batch
- Notification retried with exponential backoff + jitter
- Late acceptance after timeout → Redis state validation rejects it

**Fallback for internet-down scenario:**
- SMS via Twilio/SNS as fallback channel
- If APN/FCM fails N times → trigger SMS
- Less real-time but ensures driver is reached for critical notifications

---

### Q8 — Kafka Partition Key Strategy

**Q: Why rideId? When driverId or cellId?**

**A:**

| Option | Verdict | Reason |
|---|---|---|
| `cellId` | ❌ Wrong | Geographic skewness — dense cities create hot partitions |
| `driverId` for location topic | ✅ Correct | Drivers exist without rides; ordered processing per driver |
| `rideId` for ride events topic | ✅ Correct | State transitions must be ordered per ride |

**Two separate topics, independent partition strategies:**
- `driver.location.updates` → partition by `driverId`
- `ride.state.changes` → partition by `rideId`

> **Key insight:** Different topics have different access patterns and different ordering requirements. Never conflate them into a single topic to avoid partition key conflicts.

---

### Q9 — Kafka 5-Minute Delay Degradation

**Q: What degrades? What must not?**

**A:**

**Must NOT degrade (zero Kafka dependency):**
- Matchmaking — Redis serves H3 independently
- Ride booking — API GW → Ride Management is synchronous
- ETA and Fare Estimation — independent of Kafka
- Payment initiation — async, triggered by rider/driver directly

**Degrades gracefully (acceptable):**
- Location updates in Redis — slightly stale, H3 cell changes infrequent
- Snowflake analytics data — delayed, not user-facing
- Notification delivery — slight delay, not catastrophic

**Why topic isolation is the key:**
- Location topic: 1M × 12 events/min = extremely high throughput
- Ride management topic: ~100-500 state changes/sec — trivially low
- Separating them means location storm **never** touches ride state pipeline

---

### Q10 — Out-of-Order GPS Updates

**Q: T=10 location arrives after T=15. Prevent stale overwrite?**

**A:**

Three-layer solution:

**Layer 1 — Server-side timestamping:**
- Location Update Service stamps server time at ingestion — before Kafka publish
- Client timestamps untrustworthy (GPS drift, battery saver, manipulated clocks)
- Fully server-authoritative

**Layer 2 — Conditional Redis write:**
```
IF incoming_event.server_timestamp > redis.stored_timestamp:
    UPDATE redis location
ELSE:
    DISCARD (stale event)
```

**Layer 3 — Kafka partition ordering:**
- Location events partitioned by `driverId`
- All events for one driver arrive in order to same consumer
- Out-of-order is rare but handled by Layer 2

---

### Q11 — H3 Cell Expansion Strategy

**Q: No drivers in initial cell. How does search expand?**

**A:**

```
1. Start at precomputed resolution for area (airport=fine, highway=coarse)
2. Query all drivers in current cell
3. count < threshold → expand to kRing(1) = 6 neighbours
4. Still insufficient → kRing(2) = 18 cells
5. Continue until threshold met OR max search radius exceeded
6. Return to rider with ETA adjusted for actual pickup distance
```

**Precomputed starting resolution (key optimization):**

| Area Type | Starting Resolution | Reason |
|---|---|---|
| Airport / Stadium | Fine (small cells) | High driver density, precise matching |
| City center | Medium | Balanced density |
| Highway / Rural | Coarse (large cells) | Low density, skip fine resolution entirely |

Starting level trained on historical ride request density per H3 cell — updated by batch job periodically.

> **Why this matters:** Without precomputed starting resolution, every request in a desert starts at fine resolution and expands 10+ times. Precomputation reduces Redis lookups by 80%+ in low-density areas.

---

### Q12 — Redis Stale + DynamoDB Correct

**Q: Redis stale, DynamoDB has correct data. How does dispatch behave?**

**A:**

**Scenario 1 — Pipeline broken (Kafka lag / Redis down):**
- Circuit breaker detects Redis unavailability
- H3 index switches to DynamoDB connector as fallback
- DynamoDB lags by Kafka backpressure window (seconds to minutes)
- Matchmaking continues with slightly stale data — acceptable during brief outage
- Redis recovery → circuit closes → H3 switches back

**Scenario 2 — Single missed update:**
- One driver's location is 5 seconds stale in Redis
- H3 cell granularity requires hundreds of meters of movement to change cells
- At peak traffic speeds in dense cities — extremely unlikely in 5 seconds
- Next update self-corrects — no action needed
- Acceptable trade-off

---

### Q13 — State Machine Idempotency

**Q: Same Kafka event delivered twice. Invalid transitions. How do you handle?**

**A:**

**Three layers of protection:**

**Layer 1 — Idempotent event keys:**
```
Every Kafka event carries unique eventId
→ Check Redis before processing: has eventId been seen?
→ YES: drop silently
→ NO: process + store eventId with TTL
```

**Layer 2 — State validation before write:**
```
Before any DB write:
→ Fetch current state from DB
→ Validate: is this transition valid per state machine?
→ COMPLETED → COMPLETED: REJECT
→ CANCELLED → STARTED: REJECT
→ Only valid transitions proceed
```

**Layer 3 — Crash recovery:**
- Service crashes after DB write but before Kafka offset commit
- On restart, Kafka redelivers same event
- Layer 2 catches it — state already `COMPLETED`, transition rejected
- Checkpoint state (Flink-style) in S3 for complex multi-step transitions

---

### Q14 — Driver Accepts + Rider Cancels Race Condition

**Q: Both events arrive simultaneously. Which wins? How is losing side notified?**

**A:**

Redis + Lua scripts handle this atomically — no distributed coordination needed.

**Case 1 — Cancel arrives first:**
```
Cancel event → Redis atomically sets state = CANCELLED
Driver acceptance arrives → state validation: CANCELLED → DRIVER_ASSIGNED = INVALID
→ Rejection returned to matchmaking
→ Notification: "Ride was cancelled by rider"
```

**Case 2 — Acceptance arrives first:**
```
Lua script atomically acquires lease → state = DRIVER_ASSIGNED
Cancel event arrives → treated as post-acceptance cancellation
→ Ride: DRIVER_ASSIGNED → CANCELLED
→ Cancellation fee applies per business logic
→ Notification: "Rider cancelled after you were assigned"
```

**Why Lua scripts?**
- Execute atomically on Redis — no operation can interleave
- Check-and-set in single atomic operation
- Eliminates race window entirely

---

### Q15 — Region Goes Down Entirely

**Q: 500 active rides mid-trip. Thousands of incoming requests. What happens?**

**A:**

**Immediate response:**
- GeoDNS detects region failure → redirects new traffic to nearest healthy region
- Existing WebSocket connections drop → clients retry with exponential backoff + jitter
- Jitter prevents 500 rides + thousands of users reconnecting simultaneously (thundering herd)

**Data availability:**
- DBs asynchronously replicated cross-region — Hyderabad Postgres has ride data
- Replication lag edge case: ride `COMPLETED` in Bengaluru moments before failure → Hyderabad thinks `STARTED`
- **Payment is async** — rider can pay anytime — sidesteps immediate consistency requirement
- App surfaces ambiguity proactively: "We lost connection during your ride, please confirm trip status"
- Proactive inconsistency detection: if ride state hasn't updated in N seconds despite active WebSocket → prompt user

**Matchmaking recovery:**
- H3 repopulates from DynamoDB initially (circuit breaker fallback)
- Drivers reconnect → Redis repopulates within 5-10 seconds
- Hyderabad pods autoscale to handle combined load

**Ideal improvement:**
- **Synchronous replication** for ride state (low write volume, high consistency requirement)
- **Async replication** for location data (high write volume, eventual consistency acceptable)
- Eliminates replication lag problem for critical state entirely

---

### Q16 — FIFO Queue + Thundering Herd on Retry

**Q: Dropped requests retry simultaneously — how do you prevent another thundering herd?**

**A:**
**Exponential backoff with jitter:**

```
base_delay = 1s
max_delay = 32s
jitter = random(0, base_delay)

retry_delay = min(base_delay × 2^attempt, max_delay) + jitter
```

- Without jitter: all 10,000 dropped requests retry at T+1s, T+2s, T+4s simultaneously
- With jitter: retries spread across a time window — natural load smoothing
- System drains queue, new pods come up, retries succeed progressively

---

## Important Keywords & Concepts

### Geo-Indexing
| Term | Definition in Uber Context |
|---|---|
| **H3** | Uber's hexagonal hierarchical geo-indexing system. Divides earth into hexagonal cells at multiple resolutions. Used to find all drivers within a geographic area efficiently |
| **kRing(n)** | H3 function returning all cells within n rings of a given cell. kRing(1) = 6 neighbours, kRing(2) = 18 cells |
| **Cell resolution** | H3 has 16 resolutions (0-15). Higher resolution = smaller cells. Precompute starting resolution per area based on historical driver density |

### Distributed Systems Patterns
| Term | Definition in Uber Context |
|---|---|
| **Circuit Breaker** | Detects downstream failure on first request, switches to fallback. States: Closed → Open → Half-Open → Closed. Used for Google Maps, Stripe, Redis |
| **Lease-based lock** | Time-limited lock in Redis. Auto-expires on TTL — prevents deadlock if holder crashes |
| **Lua scripts (Redis)** | Atomic multi-step operations on Redis. Used for check-and-set matchmaking locks |
| **Idempotent event key** | Unique key per event stored in Redis. Enables exactly-once processing on top of at-least-once Kafka delivery |
| **Dead Letter Queue** | Queue for events that failed processing after N retries. Prevents message loss, enables manual inspection |
| **Server-side timestamping** | Stamp events with server time at ingestion point. Prevents client clock skew from corrupting ordering |

### Caching & Storage
| Term | Definition in Uber Context |
|---|---|
| **Redis quorum** | Replication across N nodes. Write succeeds only if majority acknowledge. Prevents data loss on single node failure |
| **AOF persistence** | Append-Only File — Redis durability mode. Every write logged to disk. Survives Redis restart |
| **Partial index** | Postgres index on subset of rows (e.g., WHERE status = 'ACTIVE'). Avoids index churn from completed rides |
| **Composite index** | Index on multiple columns (region, status, created_at). Efficiently serves multi-column WHERE clauses |

### Kafka
| Term | Definition in Uber Context |
|---|---|
| **Topic isolation** | Separate topics for different event streams. Prevents high-throughput location events from creating backpressure on low-throughput ride state events |
| **Partition key** | Determines which partition an event goes to. Same key = same partition = guaranteed ordering for that key |
| **Consumer group scaling** | Add more consumer workers to drain partitions faster. Preferred over adding partitions dynamically |
| **Kafka offset commit** | Consumer's acknowledgement of processed message. Uncommitted offset = redelivery on crash |

### Availability Patterns
| Term | Definition in Uber Context |
|---|---|
| **Heartbeat TTL** | Driver availability stored in Redis with expiry. Key expires = driver offline. Separate from location updates |
| **Exponential backoff + jitter** | Retry delay = base × 2^attempt + random(). Spreads retries across time window, prevents thundering herd |
| **Predictive autoscaling** | Pre-scale pods before known peak events (Monday mornings, concerts). Kubernetes HPA + scheduled scaling |
| **GeoDNS** | DNS-level routing to nearest healthy region. First line of defence for regional failover |

---

## Quick Reference NFR Block

```
Availability:    99.99% — GeoDNS, regional redundancy, circuit breakers
Latency:         Matchmaking < 2s — Redis H3 in-memory lookup
                 Location update < 5s — Kafka async pipeline
Consistency:     Strong for ride state — Redis Lua + state validation
                 Eventual for location — 5s update window acceptable
Scalability:     100K peak drivers — Redis cluster, Kafka partitions by driverId
                 500 peak rides/sec — Postgres regional partitions
Durability:      No ride lost — Kafka retention, DynamoDB persistence, Postgres replication
```

---

## Concepts Checklist

### Must Know Before This Interview
- [ ] H3 hexagonal geo-indexing and cell expansion
- [ ] Circuit breaker pattern (Open / Half-Open / Closed)
- [ ] Lua scripts for Redis atomic operations
- [ ] Kafka partition key strategy and ordering guarantees
- [ ] Idempotent event processing (at-least-once + idempotent key = exactly-once)
- [ ] Exponential backoff with jitter
- [ ] Redis TTL for heartbeat / lease expiry
- [ ] State machine validation before DB write
- [ ] Server-side timestamping for event ordering
- [ ] Dead letter queue for failed async operations

### Advanced Topics (L5+ Signal)
- [ ] Correlated failure analysis (Redis stress + Kafka lag simultaneously)
- [ ] Synchronous vs async cross-region replication trade-offs
- [ ] Precomputed H3 starting resolution based on historical density
- [ ] Consumer group auto-scaling vs dynamic partition addition
- [ ] Feature store for ML pricing models
- [ ] Surge pricing feedback loop (demand suppression detection)

---

## Ride & Payment State Machines

```
RIDE STATE MACHINE:
INITIATED → DRIVER_ASSIGNED → STARTED → COMPLETED
     ↓              ↓             ↓
 CANCELLED      CANCELLED    CANCELLED

PAYMENT STATE MACHINE:
NOT_READY → PAYMENT_STARTED → PAYMENT_COMPLETED
                   ↓
            PAYMENT_FAILED → PAYMENT_STARTED (retry)
                   ↓
           PAYMENT_CANCELLED
```

---

## Entities & APIs

### Core Entities
```
Rider: riderId, riderName, email, phone, createdAt
Driver: driverId, driverName, driverLicense, contact, createdAt, carNo, carModel
Ride: rideId, riderId, driverId, createdAt, fromLat, fromLong, toLat, toLong,
      status, amount, currency, paymentStatus, paymentMode
Location: driverId, timestamp, lat, long
Fare: fareId, rideId, amount, currency, surgeMultiplier
```

### External APIs
```
GET  /v1/getEtaFareEstimation/{fromLat}/{fromLong}/{toLat}/{toLong}
     → { fare, distance, mode, fromLat, fromLong, toLat, toLong, timestamp }

POST /v1/bookRide
     → { fare, distance, mode, fromLat, fromLong, toLat, toLong, timestamp }

POST /v1/confirmRide
     → { rideId, driverId, status: CONFIRM/REJECT }

POST /v1/endRide
     → { rideId, driverId, timestamp }
```