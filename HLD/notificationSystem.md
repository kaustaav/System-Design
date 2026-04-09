# Notification System — High Level Design

> **Target:** L4 roles at Google / Meta / Uber | 50-70 LPA  
> **Difficulty:** Medium-High  
> **Category:** Communication Systems (C1)

---

## Table of Contents

1. [Functional Requirements](#functional-requirements)
2. [Non-Functional Requirements](#non-functional-requirements)
3. [Back-of-Envelope Estimation](#back-of-envelope-estimation)
4. [Architecture](#architecture)
5. [Core Workflows](#core-workflows)
6. [Key Design Decisions](#key-design-decisions)
7. [Deep Dive Q&A](#deep-dive-qa)
8. [Important Keywords](#important-keywords)
9. [Quick-Ref NFR Block](#quick-ref-nfr-block)
10. [Concepts Checklist](#concepts-checklist)

---

## Functional Requirements

- Send notifications to users across multiple channels: **Push (APN/FCM), SMS, Email**
- Support **transactional notifications** (OTP, order updates) and **marketing/promotional notifications**
- Support **scheduled notifications** (send at a future time)
- Support **user preference management** — users can opt out of specific notification types or channels
- Notifications are hydrated with user-specific data (name, device, email) before delivery
- System must handle **at-least-once delivery** (duplicates tolerable, loss is not)

**Out of scope:** Template creation UI, user preference management UI, analytics dashboard

---

## Non-Functional Requirements

| NFR | Target | Notes |
|-----|--------|-------|
| Transactional delivery latency | 99.9% within **10 seconds** | OTP, order updates |
| Promotional delivery latency | 99% within **5 minutes** | Bulk campaigns |
| Availability | **99.9%** | Core sending pipeline |
| Duplicate delivery rate | < **0.01%** | Idempotency keys at two layers |
| Failed delivery rate | < **0.1%** after retries | DLQ handles the rest |
| Peak spike handling | **50x** normal load | Sales events, festivals |
| OTP channel isolation | Dedicated worker pool | Zero resource sharing with promo |

---

## Back-of-Envelope Estimation

```
User Base            : 100 million users
Daily Notifications  : 1 billion / day

Throughput
──────────
Avg notifications/sec = 1B / 86,400 ≈ 11,574 ≈ ~12,000 /sec
Peak notifications/sec = avg × 5 (peak factor) ≈ 50,000 /sec

Storage (User Info DB)
──────────────────────
Data per user = ~1 KB (id, name, email, contact, registeredAt)
Total = 1 KB × 100M = 100 GB
→ PostgreSQL with partitioning is a good fit

Storage (Device & Preference DB)
─────────────────────────────────
~1 KB per user for devices + preferences
Total = 100 GB
→ PostgreSQL (same DB, separate tables)

Bandwidth
──────────
Avg notification payload = ~10 KB
Bandwidth = 50,000 /sec × 10 KB = 500 MB/s peak

Log Storage (DynamoDB)
──────────────────────
~200 bytes per log entry
1B notifications/day = 200 GB/day → use TTL-based expiry (30 days = ~6 TB)
```

---

## Architecture

```
                         ┌─────────────────────────────────┐
                         │         Scheduled               │
                         │      Notification DB            │
                         └──────────────┬──────────────────┘
                                        │ poll every 1 min
                                        ▼
 Service1 ──┐                  ┌─────────────────┐
 Service2 ──┼──► API GW ──────►│  Notification   │◄──── User Cache (Redis)
 Service3 ──┘  Rate Limit      │    Server       │      (Info, Devices,
               Load Balance    └────────┬────────┘       Preferences)
               Auth                     │                      ▲
                                        │                      │
                    ┌───────────────────┼───────────────────┐  │
                    ▼                   ▼                   ▼  │
             ┌────────────┐    ┌────────────────┐   ┌──────────────┐
             │ APN Queue  │    │  SMS Queue     │   │  Email Queue │
             │  (hi/lo)   │    │   (hi/lo)      │   │   (hi/lo)    │
             │  + DLQ     │    │   + DLQ        │   │   + DLQ      │
             └─────┬──────┘    └───────┬────────┘   └──────┬───────┘
                   │                   │                    │
                   ▼                   ▼                    ▼
             ┌──────────┐       ┌──────────┐        ┌──────────────┐
             │  APN     │       │  SMS     │        │    Email     │
             │ Workers  │       │ Workers  │        │   Workers    │
             └────┬─────┘       └────┬─────┘        └──────┬───────┘
                  │ with ack         │ with ack             │ with ack
                  ▼                  ▼                      ▼
            APN Server          SMS Server            Email Server
                  │                  │                      │
                  ▼                  ▼                      ▼
              Android/iOS          Phone                  Email
                                                              
                         ┌────────────────────┐
                         │  Log DB (DynamoDB) │◄── Workers write on success
                         └────────────────────┘

                         ┌────────────────────┐   ┌──────────────────┐
                         │  Template Cache    │◄──│   Template DB    │
                         │    (Redis)         │   │                  │
                         └────────────────────┘   └──────────────────┘

                         ┌────────────────────┐
                         │   DLQ Processor    │──► Incidents Table
                         │   (dedicated svc)  │──► PagerDuty Alert
                         └────────────────────┘──► Dead Archive
```

### Component Table

| Component | Technology | Responsibility |
|-----------|-----------|----------------|
| API Gateway | Kong / AWS API GW | Auth, rate limiting, load balancing |
| Notification Server | Stateless service (multiple instances) | Request validation, user preference check, queue routing |
| User Cache | Redis | User info, device tokens, preferences — low-latency reads |
| User DB | PostgreSQL (partitioned) | Source of truth for user data |
| Kafka Topics | Per channel per priority | `sms-critical`, `sms-low`, `email-critical`, `email-low`, etc. |
| Workers | Stateless consumers | Template hydration, provider call, ack handling |
| Template Cache | Redis | Versioned templates keyed by `templateId:version_id` |
| Template DB | PostgreSQL | Source of truth for templates with `effective_from` timestamps |
| Log DB | DynamoDB | Append-only delivery log — `notificationId`, `status`, `timestamp` |
| DLQ | Kafka DLQ per topic | Failed messages after max retries |
| DLQ Processor | Dedicated service | Retry with backoff, alert, archive |
| Scheduled Notification DB | PostgreSQL | Stores future notifications with `status`, `scheduled_time` |
| Scheduler Job | Stateless cron | Polls every 1 min, claims notifications via optimistic lock |

---

## Core Workflows

### 1. Real-Time Notification (OTP / Transactional)

```
1. Upstream service (e.g. OTP Service) sends notification request to API GW
   → Request: { userId, type: OTP, content, channels: [SMS, EMAIL], idempotencyKey }

2. API GW applies:
   → IP-level rate limiting (DDoS protection)
   → serviceId-level rate limiting (per-team quota)
   → userId-level rate limiting via Redis INCR+TTL (1 OTP/min/user)
   → Auth check (internal services only)

3. Notification Server:
   → Checks Redis dedup cache: key = userId:notificationType:windowBucket (1-min bucket)
   → If duplicate → reject with 200 OK (idempotent)
   → Queries User Cache for: email, device tokens, preferences
   → If user opted out of channel → skip that channel
   → Hydrates notification: [{ userId, emailId, content }, { userId, deviceId, content }]
   → Routes to Kafka topic by channel + priority (OTP → critical topics)

4. Worker consumes from Kafka (offset NOT committed yet):
   → Fetches template from Template Cache (key: templateId:version_id)
   → Fully hydrates notification
   → Validates OTP TTL: if current_time > otp_expires_at → drop, log EXPIRED_DROP, skip
   → Validates user preference again (second check — defense against stale cache)
   → Calls provider (APN / Twilio / SendGrid) with idempotency key
   → Receives provider ack
   → Writes success to DynamoDB log
   → Commits Kafka offset ← THIS is the ack to Kafka

5. On worker crash (before offset commit):
   → Kafka redelivers message on restart
   → Provider deduplicates via idempotency key → no duplicate send
   → Worker retries cleanly
```

### 2. Scheduled Notification

```
1. Upstream service writes notification to Scheduled Notification DB
   → Fields: id, userId, content, templateId, channels, scheduled_time, status=PENDING

2. Scheduler Job runs every 1 minute:
   → Queries: WHERE status=PENDING AND scheduled_time <= now() + 3min
   → Atomically claims each: UPDATE SET status=PROCESSING WHERE status=PENDING (optimistic lock)
   → Only the instance that wins the CAS proceeds (prevents double-processing)
   → Hands off to Notification Server

3. Notification Server processes same as real-time flow (step 3 above)

4. On scheduler downtime recovery:
   → On restart, same query returns all unprocessed notifications automatically
   → No special recovery logic needed — stateless by design
```

### 3. DLQ Processing

```
1. Worker fails to deliver after max retries → message moves to DLQ topic

2. DLQ Processor (dedicated service) reads from DLQ:
   → Checks retry_count and last_attempted_at
   → Applies exponential backoff with jitter: 5s → 30s → 2min → 10min → 30min

3. Before each retry attempt:
   → For OTPs: check current_time vs otp_expires_at
   → If expired → log EXPIRED_DROP, emit metric, close — do NOT retry

4. After threshold retries:
   → Write to incidents table
   → Trigger PagerDuty alert to on-call team
   → Move to dead-letter archive for audit and manual replay

5. User impact:
   → Multi-channel delivery (SMS + email attempted in parallel) is primary protection
   → If both channels land in DLQ and OTP expires → user re-requests OTP
   → System surfaces clean failure state — never silently retries expired data
```

---

## Key Design Decisions

### 1. Priority Queue Simulation in Kafka

Kafka doesn't natively support priority. We simulate it with **separate topics per channel per priority tier**.

| Topic | Consumers | Used for |
|-------|-----------|----------|
| `sms-critical` | Dedicated isolated pool | OTP |
| `sms-high` | Shared high-priority pool | Order updates, alerts |
| `sms-low` | Shared low-priority pool | Promotional |
| `email-critical` | Dedicated isolated pool | OTP |
| `email-low` | Shared low-priority pool | Newsletters |

Partitioning within each topic: by `userId` — preserves per-user ordering, prevents one noisy user starving others.

---

### 2. Idempotency — Two Layers

| Layer | Mechanism | Protects Against |
|-------|-----------|-----------------|
| Ingestion (Notification Server) | Redis dedup: key = `userId:notificationType:windowBucket` | Upstream service retrying the same request |
| Provider call (Worker) | Idempotency key passed to Twilio/FCM/SendGrid | Worker crash-and-redeliver causing duplicate send |

**Why window bucket, not raw timestamp?**  
Raw timestamp changes on retry → dedup miss. A 1-minute bucket stays the same for all retries within the OTP validity window.

---

### 3. Cache Invalidation — Transactional Outbox Pattern

Used for both **User Preference Cache** and **Template Cache**.

```
Write path:
  DB write + outbox table entry (atomic, same transaction)
      → CDC listener picks up outbox event
      → Publishes cache invalidation to Kafka
      → Cache updater invalidates Redis

Why outbox?
  → Guarantees invalidation event is never lost even on mid-write crash
  → Without outbox: service crashes after DB write but before publishing → stale cache forever
```

**Residual race window:** Between DB write and cache invalidation completing, a stale read is possible. Fix: **second preference check inside the worker** before calling the provider. Acceptable trade-off — at most one notification slips through.

---

### 4. Template Versioning

| Field | Purpose |
|-------|---------|
| `version_id` | Unique per template revision |
| `effective_from` | Timestamp when this version becomes active |

Workers request template by `templateId + current_time` — cache serves the version whose `effective_from <= current_time`. Old version stays valid until new version's `effective_from` passes. Zero-downtime updates. For immediate changes (security patches): set `effective_from = now()`.

---

### 5. Rate Limiting — Three Tiers

| Tier | Key | Protects Against |
|------|-----|-----------------|
| IP-level | Client IP | External DDoS, abuse |
| serviceId-level | Upstream service identifier | One team's bug flooding the pipeline |
| userId-level | userId (embedded in notificationId) | Business logic cap — 1 OTP/min/user regardless of instance count |

userId-level limiting uses **Redis INCR + TTL**. All service instances share the same Redis counter — horizontal scaling is fully transparent.

---

### 6. Scheduler Recovery — Stateless Design

```
Scheduler is stateless. Recovery is automatic:

ON RESTART:
  Query: WHERE status=PENDING AND scheduled_time <= now() + 3min
  → Returns all previously missed notifications
  → No special recovery logic

PREVENT DOUBLE-PROCESSING (multiple scheduler instances):
  Optimistic lock: UPDATE SET status=PROCESSING WHERE status=PENDING
  → Only the CAS winner proceeds
  → Cheaper than Redis distributed lock (no extra network hop, no SPOF)

LATE NOTIFICATION HANDLING:
  Notification Server checks: scheduled_time + ttl > current_time at ingestion
  → If expired → drop and log
  → Promotional TTL: hours (5-min delay is fine)
  → Transactional TTL: tighter (configurable per type)
  → OTPs: never scheduled, bypass this path entirely
```

---

## Deep Dive Q&A

### Q1 — Scheduler downtime recovery

**Q:** Your scheduler polls the DB every minute. It goes down for 5 minutes. How does it recover without missing or double-sending notifications?

**A:** The scheduler is intentionally stateless. Every notification has a status field — PENDING, PROCESSING, SENT, FAILED — stored in the DB. On restart, it queries `WHERE status=PENDING AND scheduled_time <= now() + 3min`. This naturally returns all previously missed notifications too — no special recovery logic needed.

To prevent double-processing across multiple scheduler instances running in parallel, use an optimistic lock at the DB level — atomic `UPDATE SET status=PROCESSING WHERE status=PENDING`. Only the instance that wins the CAS proceeds. This is cheaper than a distributed Redis lock — no extra network hop, no SPOF.

---

### Q2 — Late notification handling

**Q:** Scheduler recovers after 5 minutes. Some notifications are 4 minutes late. Send, drop, or defer?

**A:** Each notification type has a configurable TTL. The Notification Server checks `scheduled_time + ttl > current_time` at ingestion. If expired, drop and log it.

Promotional notifications have a generous TTL (hours) — a 5-minute delay is acceptable, send them. Transactional scheduled notifications (order reminders) have a tighter TTL. OTPs are never in this path — they bypass the scheduler entirely and go directly from upstream services to the Notification Server via the real-time pipeline.

> **Follow-up: What about OTPs getting delayed via the scheduler?**  
> OTPs are never scheduled. They are fire-and-forget from upstream services with their own retry. The scheduler only handles promotional and pre-planned transactional notifications. Scheduler downtime has zero impact on OTP latency — the two pipelines are architecturally decoupled.

---

### Q3 — OTP TTL vs scheduled delay

**Q:** You handle both OTPs and promotional notifications. Scheduler gets delayed 5 minutes. What's the TTL on an OTP that gets delayed?

**A:** OTPs are never scheduled — they hit the Notification Server directly from upstream services, bypass the scheduler entirely, and land in the high-priority Kafka topic. The two pipelines are completely separate. Scheduler downtime, promo blast backpressure, and template cache misses on the scheduled path cannot affect OTP latency. This architectural separation is the correct answer — not a TTL number.

---

### Q4 — Priority inside Kafka

**Q:** You said queues are classified by priority and Kafka is used. Show me exactly how that works at the implementation level.

**A:** Kafka doesn't support native priority. We simulate it with separate topics per channel per priority tier: `sms-critical`, `sms-low`, `email-critical`, `email-low`, etc. The Notification Server tags each notification at ingestion based on type — OTP = critical, transactional = high, promotional = low — and routes to the corresponding topic.

Workers for critical topics are a dedicated, isolated pool. They never share resources with low-priority workers. This guarantees OTPs are never blocked by promo blast backpressure. Partitioning within each topic is by `userId` to preserve per-user ordering and prevent one noisy user from starving others.

---

### Q5 + Q6 — Consumer crash + duplicate OTP on retry

**Q:** Worker pulls an OTP, sends to provider, crashes before receiving ack. Kafka offset not committed. Message redelivered. OTP gets sent twice. How do you handle this?

**A:** Worker flow: consume from Kafka → construct from template → send to provider → receive provider ack → write success to DynamoDB log → commit Kafka offset. If the worker crashes between send and ack, the Kafka offset is not committed — message is redelivered. This is at-least-once delivery by design.

To prevent actual duplicate sends, every notification carries an idempotency key (`userId:notificationType:windowBucket` — bucketed to 1-minute windows, not raw timestamp). This key is passed to the provider. Providers like Twilio and FCM deduplicate on this key — even if the worker retries, the provider blocks the second send. This is defense layer 1.

Defense layer 2: Redis dedup at Notification Server ingestion blocks upstream service retries within the same OTP window before they even reach Kafka.

> **Follow-up: Who generates the idempotency key and what is it made of?**  
> Generated by the upstream microservice as `userId:notificationType:windowBucket`. Using a 1-minute time bucket instead of a raw timestamp is critical — raw timestamps break dedup on retries since the timestamp changes between attempts. A bucketed key stays the same for all retries within the valid OTP window.

> **Follow-up: Upstream service retries after 30 seconds — different timestamp, same OTP window. Dedup misses it?**  
> No. The key uses a 1-minute time bucket, not a raw timestamp. A retry at 30 seconds falls in the same bucket — same key, same dedup hit. After 1 minute, it's a legitimately new OTP request — correct to treat it as new. OTP regeneration is also rate-limited to once per minute at the upstream service level, so a new key after 1 minute is always a real new OTP.

---

### Q7 — Stale user preference cache

**Q:** User opts out of promotional notifications. What's your cache invalidation strategy? What if a promo lands during the invalidation window?

**A:** Cache invalidation uses the transactional outbox pattern: preference update writes to DB and outbox table atomically in one transaction. A CDC listener picks up the outbox event and publishes a cache invalidation message to Kafka. The cache updater consumes this and invalidates Redis. The outbox pattern guarantees the invalidation event is never lost even on mid-write service crash.

For the small race window between DB write and cache invalidation completing: a second preference check inside the worker before calling the provider acts as a safety net — a cheap Redis read that catches the race. Acceptable trade-off: at most one promotional notification may slip through in a multi-second window. This is a UX concern, not a correctness issue.

> **Follow-up: Stale cache window — promo lands to opted-out user. What happens?**  
> The second preference check at the worker level catches it before the provider call. Even if the Notification Server routed it to the queue with stale data, the worker re-validates against the now-updated Redis preference before sending. Two checkpoints, two chances to catch the race.

---

### Q8 — Template versioning

**Q:** A legal disclaimer changes in an email template. Template DB updated. Cache has old version. How do you handle versioning and cache consistency?

**A:** Templates are versioned entities — each has a `version_id` and `effective_from` timestamp. Workers request template by `templateId + current_time`. The cache stores all active versions keyed by `templateId:version_id`. On a template update, the outbox pattern publishes the new version to cache — the old version stays valid until its `effective_from` timestamp passes. Zero-downtime template updates, no invalidation race, full auditability.

Legal disclaimer changes are not instant — they come with a future effective timestamp, so the system serves the right version at the right time. For immediate-effect changes (security patches), force-invalidate by bumping the version and setting `effective_from = now()`.

---

### Q9 — DLQ handling + OTP TTL expiry

**Q:** A notification lands in the DLQ. Who reads it? How does it get retried? What happens to an OTP that's been in the DLQ past its TTL?

**A:** DLQ has a dedicated processor service — isolated from the main worker pool. It reads messages, checks `retry_count` and `last_attempted_at`, and applies exponential backoff with jitter (5s → 30s → 2min → 10min → 30min). After a configurable threshold of retries, it writes to an incidents table, triggers a PagerDuty alert, and moves the message to a dead-letter archive for audit and manual replay.

For OTPs specifically: before any retry attempt, the worker performs a TTL validation check — compare `current_time` against `otp_expires_at`. If expired, drop the retry immediately. Log it as `EXPIRED_DROP`, emit a metric, close the incident. Multi-channel redundancy (SMS + email attempted in parallel) is the primary protection. If both channels fail completely, the OTP has likely expired by retry time — user re-requests.

> **Follow-up: OTP expires in DLQ — user never got their OTP. What's the fallback?**  
> Multi-channel delivery means both SMS and email were attempted. If both failed and the OTP has now expired, the system surfaces a clean failure state rather than silently retrying stale data. The user re-initiates the OTP request. Never retry an expired OTP — it's useless and confusing for the user to receive an already-expired code.

---

### Q10 — Rate limiting strategy

**Q:** A legitimate internal service has a bug — 10,000 OTP requests/second for the same user. Walk through your full rate limiting strategy.

**A:** Three tiers of rate limiting at the API Gateway:

1. **IP-level** — external abuse and DDoS protection only. Breaks immediately for horizontally scaled internal services.
2. **serviceId-level** — each upstream team gets a configurable quota. Prevents one team's bug from overwhelming the notification pipeline regardless of how many instances they're running.
3. **userId-level** — business logic cap. One OTP per minute per user enforced via Redis INCR + TTL. All service instances share the same Redis counter, so horizontal scaling is fully transparent.

`notificationId` encodes `userId` — so rate limiting on `notificationId` is functionally userId-level rate limiting. Always state this explicitly in an interview: don't assume the interviewer reads your schema.

> **Follow-up: 50 instances each sending 200 req/sec — 50 different IPs. IP-based limiting misses it. What now?**  
> IP-based limiting was never the right tool for this — it's infrastructure-level, not business-entity-level. The correct answer is userId-level rate limiting in Redis. Since `notificationId` encodes `userId`, rate limiting on `notificationId` is effectively userId-level. All 50 instances share the same Redis counter for a given `userId` — one limit, one source of truth, horizontally transparent.

---

## Important Keywords

### Delivery & Reliability
| Term | Definition in this system |
|------|--------------------------|
| At-least-once delivery | Messages may be delivered more than once — loss is not acceptable, duplicates are tolerable |
| Idempotency key | `userId:notificationType:windowBucket` — ensures same notification isn't sent twice even on retry |
| Window bucket | Time-bucketed key component (1-min window) — prevents raw timestamp causing dedup miss on retry |
| DLQ (Dead Letter Queue) | Kafka topic that receives messages after max retry threshold — consumed by dedicated DLQ Processor |
| Exponential backoff with jitter | Retry delays that grow exponentially (5s→30s→2min...) with random jitter to prevent thundering herd |
| EXPIRED_DROP | Log status when an OTP is discarded because its TTL has passed before retry |

### Queue & Kafka
| Term | Definition in this system |
|------|--------------------------|
| Priority simulation | Separate Kafka topics per channel per priority tier (`sms-critical`, `sms-low`) — Kafka has no native priority |
| Kafka offset commit | The "ack to Kafka" — committing the offset tells Kafka the message was processed. Always the last step. |
| Partition key | `userId` within each topic — preserves per-user ordering, prevents noisy-neighbor starvation |
| Consumer group | Each channel's worker pool is a separate consumer group — independent scaling and backpressure |

### Caching & Consistency
| Term | Definition in this system |
|------|--------------------------|
| Transactional outbox pattern | Write to DB + outbox table atomically → CDC picks up event → publishes cache invalidation → prevents lost invalidations on crash |
| Defense-in-depth | Two preference checks (Notification Server + Worker) to catch stale cache race window |
| Template versioning | Templates stored with `version_id` + `effective_from` — cache serves correct version by time, zero-downtime updates |
| Write-through invalidation | On DB write, outbox pattern ensures cache is eventually invalidated — not TTL-based |

### Rate Limiting
| Term | Definition in this system |
|------|--------------------------|
| Three-tier rate limiting | IP (DDoS) → serviceId (team quota) → userId (business logic) |
| Redis INCR + TTL | Atomic counter per userId in Redis — all instances share it, horizontal scaling transparent |
| Entity-level vs IP-level | Rate limit on business entity (userId/serviceId) first — IP breaks for horizontally scaled services |

### Scheduling
| Term | Definition in this system |
|------|--------------------------|
| Stateless scheduler | Scheduler holds no in-memory state — recovery is automatic via DB status query on restart |
| Optimistic lock (CAS) | `UPDATE SET status=PROCESSING WHERE status=PENDING` — prevents double-processing without distributed lock |
| Notification TTL | Configurable per type — Notification Server drops expired notifications at ingestion |

---

## Quick-Ref NFR Block

```
┌─────────────────────────────────────────────────────────────────┐
│  NOTIFICATION SYSTEM — SLO REFERENCE                           │
├─────────────────────────────────────────────────────────────────┤
│  Transactional delivery  │  99.9% within 10 seconds            │
│  Promotional delivery    │  99% within 5 minutes               │
│  System availability     │  99.9%                              │
│  Duplicate rate          │  < 0.01%                            │
│  Failed delivery rate    │  < 0.1% after retries               │
│  Peak spike capacity     │  50x normal load                    │
│  OTP worker pool         │  Dedicated — zero sharing           │
│  Throughput (avg)        │  ~12,000 notifications/sec          │
│  Throughput (peak)       │  ~50,000 notifications/sec          │
│  User storage            │  ~100 GB (PostgreSQL)               │
│  Log storage             │  ~200 GB/day (DynamoDB + TTL)       │
└─────────────────────────────────────────────────────────────────┘
```

---

## Concepts Checklist

### Core Concepts (must know before this system)
- [ ] Kafka — topics, partitions, consumer groups, offset commit
- [ ] Redis — data structures, TTL, INCR atomicity
- [ ] CAP theorem — eventual consistency is acceptable here
- [ ] Transactional outbox pattern
- [ ] Idempotency — what it means, how to enforce it
- [ ] Exponential backoff with jitter

### System-Specific Concepts (covered in this design)
- [ ] Priority simulation in Kafka via separate topics
- [ ] Two-layer idempotency (ingestion + provider)
- [ ] Window-bucketed idempotency keys
- [ ] Stateless scheduler with optimistic lock recovery
- [ ] Template versioning with `effective_from`
- [ ] DLQ processor as a dedicated service
- [ ] Three-tier rate limiting (IP → serviceId → userId)
- [ ] Defense-in-depth preference checking
- [ ] Kafka offset commit as the transaction boundary
- [ ] TTL validation before provider call (OTP expiry)
- [ ] Multi-channel redundancy as primary reliability primitive

### Interview Traps to Avoid
- ❌ Saying "rate limit by IP" when services are horizontally scaled
- ❌ Using raw timestamp in idempotency key (breaks on retry)
- ❌ Committing Kafka offset before receiving provider ack (silent message loss)
- ❌ Generic NFRs without numbers ("reliable", "scalable")
- ❌ Missing DLQ Processor as an explicit component on the diagram
- ❌ Not separating OTP pipeline from scheduled notification pipeline
- ❌ Forgetting second preference check at worker level