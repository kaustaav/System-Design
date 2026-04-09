# Instagram / Facebook — System Design

A complete reference for interview prep and study. Covers requirements, estimations, architecture, key workflows, and deep-dive Q&A with follow-ups.

---

## Table of Contents

1. [Functional Requirements](#1-functional-requirements)
2. [Non-Functional Requirements](#2-non-functional-requirements)
3. [Back-of-Envelope Estimation](#3-back-of-envelope-estimation)
4. [High-Level Architecture](#4-high-level-architecture)
5. [Core Workflows](#5-core-workflows)
   - [Post Write Path](#51-post-write-path)
   - [Feed Read Path](#52-feed-read-path)
   - [Likes Flow](#53-likes-flow)
   - [Media Upload & Retrieval](#54-media-upload--retrieval)
6. [Key Design Decisions](#6-key-design-decisions)
7. [Q&A — Interview Style](#7-qa--interview-style)

---

## 1. Functional Requirements

**In scope:**
- Users can upload photos and videos as posts
- Users can follow other users (unidirectional — like Instagram)
- Users see a feed of posts from people they follow
- Users can like and comment on posts
- Users can search for other users and post content

**Out of scope:**
- Chat / direct messaging
- People / friend suggestions
- UI / frontend
- Ad serving

---

## 2. Non-Functional Requirements

| Requirement | Target |
|---|---|
| Feed load (p99) | < 500ms |
| Post upload acknowledgement | < 200ms |
| Media load on CDN hit (p95) | < 1s |
| Like count eventual consistency | < 10 seconds |
| Feed consistency (new post visible) | Eventual, < 30 seconds |
| Availability | 99.99% (~52 min downtime/year) |
| Peak write throughput | ~35,000 posts/sec |
| Peak read throughput | ~51,000 feed loads/sec |
| Media storage (10 years, 3 resolutions) | ~110 EB |
| Unfollow propagation | < 1 feed refresh cycle |

**Key consistency decisions:**
- Feed: eventually consistent — acceptable for social media
- Like count: eventually consistent — users don't mind if count is 5–10s stale
- Post durability: strong — a post must never be lost after 200 OK is returned
- Follow/unfollow: cache-first write, propagates within seconds

---

## 3. Back-of-Envelope Estimation

**Given:**
- DAU: 500 million
- MAU: 2 billion
- Avg posts per DAU: 2/day
- Read/write ratio: 100:1
- Photo size (compressed): 1 MB
- Video size (compressed): 50 MB
- Photo/video split: 80% / 20%
- Avg followers: 1,000 (celebrities: 50M+)
- Feed refreshes per DAU: 3/day

**Write QPS:**
```
Post QPS    = 500M × 2 / 86,400 ≈ 11,500/s
Peak QPS    = 11,500 × 3        ≈ 35,000/s
```

**Read QPS:**
```
Feed QPS    = 500M × 3 / 86,400 ≈ 17,000/s
Peak QPS    = 17,000 × 3        ≈ 51,000/s
```

**Storage:**
```
Posts over 10 years = 500M × 2 × 365 × 10 = 3.65 trillion posts

Avg media per post  = 1MB × 0.8 + 50MB × 0.2 = 10 MB
Raw media storage   = 3.65T × 10MB            ≈ 36.5 EB

With 3 resolutions (thumbnail, medium, full) ≈ 110 EB ceiling
User data (2B users × ~3KB)                  ≈ 6 TB
```

---

## 4. High-Level Architecture

```
                        ┌─────────────┐
User ──► GeoDNS ──────► │  API Gateway │ (LB + Auth + Rate-limit)
                        └──────┬──────┘
                               │
                        ┌──────▼──────┐
                        │ Web Servers  │
                        └──┬──┬──┬──┬─┘
          ┌────────────────┘  │  │  └────────────────┐
          ▼                   ▼  ▼                   ▼
   Post Service        Feed Service         Relationship      Engagement
  (write · Snowflake)  (hydration+rank)       Service          Service
          │                   │  │                │                │
          ▼                   │  │                ▼                ▼
  Post DB (Dynamo)             │  │         Rel DB (Dynamo)    Kafka (Eng)
  Post Cache (Redis)           │  │         Rel Cache (Redis)      │
          │                   │  │         [celeb | regular]   Likes Workers
   DynamoDB Streams            │  │                │                │
          │                   │  └── celeb posts ──┘           Likes DB
          ▼                   │         (single-flight-fetch)  (sharded)
    Kafka (Post Events)        │                                    │
          │                   │                                 Flink
    Fanout Workers             │                                    │
       │        │              │                             Agg Likes Count DB
       ▼        ▼              ▼
  Feed Cache  Feed DB   [merged + ranked]
  (Redis)   (Dynamo)

MEDIA LAYER
  Post Service ──(async)──► Transcoder (multi-res + manifest)
                                       │
                                       ▼
                                  S3 (Media)
                                       │
                                  Global CDN
                                       │
                                  Edge CDN (single-flight-fetch)
```

**Component legend:**

| Component | Technology | Reason |
|---|---|---|
| User DB | Postgres | Relational, 6TB manageable, low write volume |
| Post DB | DynamoDB | Key-value, single-digit ms reads, AWS ecosystem |
| Feed DB | DynamoDB | High write throughput from fanout workers |
| Rel DB | DynamoDB | Simple key-value access pattern |
| Likes DB | DynamoDB | Sharded writes, high throughput |
| All caches | Redis | Sub-millisecond reads, TTL support |
| Queue | Kafka / SQS | Durable, async event streaming |
| Stream aggregation | Flink | Near-real-time windowed aggregation |
| Media storage | S3 | Object storage at EB scale |
| ID generation | Snowflake | Time-ordered, distributed, no central DB |
| Search | Elasticsearch | Inverted index, full-text search |
| CDN | Edge + Global | Geo-distributed, low-latency media delivery |

---

## 5. Core Workflows

### 5.1 Post Write Path

```
1.  Client hits POST /post
2.  Post Service generates pre-signed S3 URL → returns to client
3.  Client uploads media directly to S3 (offloads bandwidth)
4.  Client sends metadata (caption, S3 URL) to Post Service
5.  Post Service:
      - Generates PostId via Snowflake
      - Writes to Post DB (DynamoDB) ← 200 OK returned here
      - Writes to Post Cache (Redis)
6.  DynamoDB Streams fires onPostCreated event
7.  Event reaches Kafka (Post Events)
8.  Fanout Workers consume:
      - Read follower list from Relationship Cache
      - Write postId to Feed Cache (Redis) for each follower
      - Write to Feed DB (DynamoDB) for persistence
9.  Transcoder consumes event (async):
      - Generates thumbnail, medium, full-res versions
      - Stores to S3, updates manifest in Post DB
```

**Why return 200 OK at step 5?**
User should not wait for fanout. Fanout for 1,000 followers takes time and is fully async. The post is durable after the DB write — that's the contract.

**Failure safety — DynamoDB Streams vs Outbox Pattern:**

| | DynamoDB Streams | Outbox Pattern |
|---|---|---|
| How it works | DynamoDB captures every write as a CDC event | Write outbox record atomically with the post; a relay polls and publishes to Kafka |
| Retention | 24 hours | Unlimited |
| Infrastructure | Zero — built into DynamoDB | Outbox table + relay/poller |
| Best for | AWS-native stacks | Multi-DB or high-durability requirements |

**Recommendation:** Use DynamoDB Streams for simplicity in AWS. Note the 24-hour retention ceiling — if Kafka is down for more than 24 hours, events are lost.

---

### 5.2 Feed Read Path

**Hybrid fanout — push for regular users, pull for celebrities:**

The celebrity problem: a user with 50M followers posting would generate 50M Kafka write events — causing massive backpressure and fanout worker overload. Solution: skip fanout for celebrities entirely.

```
User opens feed:
  1. Feed Service reads Relationship Cache
       → splits followees into: regular | celebrity
  2. Two async queries fire in parallel:
       A. Feed Cache (Redis)      ← pre-computed feed for regular followees
       B. Post Cache (Redis)      ← latest posts from celebrity followees
                                     (single-flight-fetch — request collapsing)
  3. Results merged + ranked in Feed Service
  4. Paginated response returned to client
```

**Celebrity detection:**
- At post-write time: Post Service checks User Cache for follower count or blue-tick flag
- Threshold (e.g. 100K followers) → post marked as celebrity, bypasses fanout
- Production-grade: maintain a **celebrity registry** (small table updated async when threshold is crossed) — decouples detection from per-post write path

**Pagination:**
- Cursor-based (last seen `postId` or `creationTime`) — never offset-based
- Offset pagination requires full table scans and breaks at scale
- Feed Cache holds more posts than one page; cursor filters from the last seen position

**Unfollow consistency:**
- Cache-first write to Relationship Cache on unfollow
- Next feed load excludes unfollowed user's posts
- Client-side in-memory filter acts as immediate fallback
- Worst case: one feed refresh cycle (~seconds)

---

### 5.3 Likes Flow

```
User taps like:
  1. Engagement Service receives like event
       - Idempotency check: reject duplicate likes
       - Validation: unlike requires prior like
  2. Publishes to Kafka (Engagement)
  3. Likes Workers consume:
       - Write to Likes DB (DynamoDB)
         Partition key: postId:shardId (e.g. postId:0 to postId:99)
         Reason: viral post at 17K likes/sec = hot partition on postId alone
  4. DynamoDB Streams → Flink
       - 5-second tumbling window
       - Aggregates delta like count
       - Writes to Aggregated Likes Count DB
       - Result: 17K writes/sec → ~1 write per 5 seconds on the counter
  5. Feed Service reads like count from Agg Likes Count DB
       - Eventually consistent within Flink window (~5–10s)
       - Acceptable tradeoff for social media
```

**Hot partition mitigation:**
Always use `postId:shardId` composite key (scatter writes across N shards). On read, scatter-gather across all shards and sum. Alternatively, apply sharding only to celebrity/viral posts detected by the celebrity registry.

---

### 5.4 Media Upload & Retrieval

**Upload (async transcoding):**
```
1. Client gets pre-signed S3 URL from Post Service
2. Client uploads original media directly to S3
3. Kafka event triggers Transcoder (async)
4. Transcoder generates:
     - Thumbnail (high CDN TTL: 30 days)
     - Medium resolution
     - Full resolution (CDN TTL: 7 days)
5. All versions stored in S3
6. Manifest file updated in Post Cache/DB
   (manifest maps postId → all resolution URLs)
7. Client selects resolution based on network conditions + screen size
   (adaptive bitrate for video via HLS/DASH)
```

**Retrieval — CDN hierarchy:**
```
Request → GeoDNS → Edge CDN (Bangalore PoP)
              │
        Cache hit → serve in ~20–50ms
              │
        Cache miss → Global CDN (~100–150ms)
              │
        Cache miss → S3 origin (~300–500ms)
```

**Thundering herd — single-flight fetch (request collapsing):**
When a viral post is brand new and no CDN has it yet, thousands of users request it simultaneously. Without collapsing, each triggers an S3 fetch.

With single-flight fetch: only the first request goes to S3; all concurrent requests wait and receive the same response when it returns. Result: 1 S3 fetch regardless of concurrent demand.

**Cold storage:**
Content not accessed in 90 days → tiered to S3 Glacier (significant cost reduction at EB scale).

---

## 6. Key Design Decisions

### Why DynamoDB over Cassandra for posts?

| | DynamoDB | Cassandra |
|---|---|---|
| Access pattern | Key-value, single-digit ms | More flexible query patterns |
| Operations | Zero — managed by AWS | Self-managed or Datastax |
| Ecosystem | DynamoDB Streams, S3, SQS | Custom CDC needed |
| Multi-region | Global Tables (built-in) | Manual topology config |
| Cost model | Per read/write unit | Cluster provisioning |

**Verdict:** DynamoDB wins in AWS-native stacks on operational simplicity. Cassandra wins if you need fine-grained replication topology control or are not in AWS.

### Why Flink over simple Kafka consumers for like aggregation?

Kafka consumers write individual like events to DB — at 17K/s for a viral post, this hammers the likes counter with 17,000 individual increments per second.

Flink applies a tumbling window (e.g. 5 seconds), batches all events within the window, and writes a single delta count to the Agg Likes DB. Result: database writes reduced from 17K/s to ~0.2/s per post.

### Why cursor-based pagination over offset?

Offset pagination: `SELECT * FROM feed LIMIT 20 OFFSET 1000` — requires the DB to scan and discard 1,000 rows on every request. At 51K feed loads/sec this is catastrophically expensive.

Cursor pagination: `WHERE creationTime < :lastSeen LIMIT 20` — starts exactly where the user left off. No scan. O(1) regardless of page depth.

### Why pre-signed S3 URLs for media upload?

If media went through Post Service: 500M users × 2 posts/day × 10MB avg = 100 PB/day through your app servers. This would require thousands of servers just for I/O.

With pre-signed URLs: the client uploads directly to S3. App servers handle only metadata. Bandwidth cost drops to near zero on the application tier.

---

## 7. Q&A — Interview Style

---

### Q1 — Hybrid fanout: how do you handle a celebrity with 50M followers?

**Question:** A celebrity with 50M followers posts a photo. Walk through what happens in your fanout design and what the problem is with naive push-based fanout.

**Answer:**

Naive push fanout generates 50M Kafka events per post. Each event is consumed by a fanout worker that writes to a follower's Feed Cache and Feed DB. At 50M followers, this causes:
- Kafka backpressure — the partition for this postId becomes a write bottleneck
- Fanout worker overload — all workers are occupied with one post
- Feed DB write spike — 50M writes in a short window

**Solution — hybrid fanout:**
- **Regular users (< threshold):** push-based. Fanout Workers write postId to each follower's Feed Cache at post time.
- **Celebrity users (≥ threshold):** pull-based. Celebrity posts are NOT fanned out. At feed-read time, Feed Service separately queries Post Cache for the latest posts from celebrities the user follows, then merges with their regular feed.

**Read path:**
```
Feed Service reads:
  A. Feed Cache (Redis) → pre-computed posts from regular followees
  B. Post Cache (Redis) → latest posts from celebrity followees
     (Relationship Cache identifies which followees are celebrities)
Merge + rank → return to client
```

**Follow-up: How does Feed Service know which followees are celebrities?**

Relationship Cache (Redis) stores each user's follow list with each followee tagged as `regular` or `celebrity`. The tag is set at follow-write time and updated asynchronously when a user crosses the follower threshold. On feed load, Feed Service reads the follow list, splits by tag, and fires two parallel queries.

**Follow-up: How do you handle the threshold transition — a user crossing from 99K to 100K followers?**

Approximate buffering is acceptable — 99.9K followers is operationally equivalent to 100K. Production approach: maintain a **celebrity registry** (a small table updated by a background job that monitors follower count changes via DynamoDB Streams). This decouples celebrity detection from the per-post write path and makes transitions explicit. Worst case if missed: a 2M-follower account triggers full fanout for one post — bounded spike, not infinite.

---

### Q2 — Post write path: when do you return 200 OK?

**Question:** Walk me through the post write path end to end. When do you return 200 OK to the user?

**Answer:**

```
1. Client requests pre-signed S3 URL from Post Service
2. Client uploads media directly to S3
3. Client sends metadata (caption, S3 URL) to Post Service
4. Post Service:
     - Generates PostId (Snowflake)
     - Writes to Post DB (DynamoDB) + Post Cache (Redis) in parallel
     ← 200 OK returned here
5. DynamoDB Streams fires onPostCreated event → Kafka
6. Fanout Workers consume → write to Feed Cache + Feed DB (async)
7. Transcoder consumes → generates resolutions → stores to S3 (async)
```

**200 OK is returned after the durable DB write, not after fanout.**

Returning after Kafka publish risks losing the post if Kafka is temporarily down. Returning after fanout makes the user wait seconds. The contract is: once the DB write succeeds, the post exists. Everything else is async.

**Follow-up: What if DynamoDB Streams fails to deliver the event to Kafka?**

DynamoDB Streams has 24-hour event retention with built-in retry. If the Kafka consumer is down for under 24 hours, events replay on recovery.

Alternative — **outbox pattern**: write the post and an outbox event record atomically. A relay polls the outbox table and publishes to Kafka, deleting records on ACK. Gives unlimited retention and works across any DB. Tradeoff: requires an extra table and a relay service. For AWS-native stacks, DynamoDB Streams is the pragmatic choice — just document the 24-hour retention limit.

---

### Q3 — Likes at scale: hot partition problem

**Question:** A viral post gets 10M likes in 10 minutes — roughly 17,000 like writes per second on a single postId. How does your system handle it?

**Answer:**

Likes are fully decoupled from Post and Feed services:

```
User taps like
→ Engagement Service (idempotency check)
→ Kafka (Engagement)
→ Likes Workers
→ Likes DB (DynamoDB)
```

**Hot partition problem:** If Likes DB is partitioned by `postId`, 17K writes/sec all hit the same partition — a DynamoDB hot partition that gets throttled.

**Solution — composite sharding:**
Use `postId:shardId` as the partition key, where `shardId = random(0, N)` at write time. 17K writes/sec spread across 100 shards = 170 writes/sec per shard — well within DynamoDB limits.

**Aggregated like count — Flink:**
```
Likes DB (sharded) → DynamoDB Streams → Flink
  - 5-second tumbling window
  - Aggregates delta count across all shards
  - Writes single count to Agg Likes Count DB
```

Result: 17K DB writes/sec → ~1 counter update per 5 seconds.

Like count shown to users is eventually consistent within the Flink window (~5–10s). Acceptable tradeoff for social media — users don't notice a 5-second lag on a count that's already in the millions.

**Follow-up: How do you detect that a post needs sharded partitioning?**

Simplest approach: always use `postId:shardId` for all posts (e.g. `postId:0` through `postId:9`). The overhead is minimal and it eliminates any need for dynamic migration. On read, scatter-gather across all shards and sum. Alternatively, apply sharding by default to all celebrity posts (already flagged in the celebrity registry) and use a monitor to detect unexpected viral spikes on regular posts.

**Follow-up: How is like idempotency enforced?**

At the Engagement Service layer:
- Duplicate like → rejected (check `userId + postId` in Likes DB or a Redis set)
- Unlike → only allowed if a prior like record exists
- This prevents double-counting in the Flink aggregation pipeline

---

### Q4 — Media retrieval: CDN hierarchy and thundering herd

**Question:** A user in Bangalore opens Instagram and scrolls through 20 posts. Trace the media read path. How do you ensure photos load within 500ms?

**Answer:**

```
Client → GeoDNS → Edge CDN (Bangalore PoP)
              ├── Cache hit  → ~20–50ms ✓
              └── Cache miss → Global CDN (~100–150ms)
                                    ├── Cache hit  → repopulates Edge
                                    └── Cache miss → S3 (~300–500ms)
                                                      repopulates Global + Edge
```

**Thundering herd — single-flight fetch:**

A new post from a celebrity has never been cached. Millions of requests hit Edge CDN simultaneously — without collapsing, each would trigger an S3 fetch.

Single-flight fetch: Edge CDN holds all concurrent requests for the same object and sends exactly one upstream fetch. When the response returns, all waiting requests receive it simultaneously. Result: 1 S3 fetch regardless of concurrent demand.

**Multiple resolutions:**
Transcoder generates thumbnail, medium, and full resolution at upload time (async). A manifest file in Post Cache maps the postId to all URLs. Client selects the appropriate resolution based on network conditions (adaptive bitrate for video via HLS/DASH).

**CDN TTL strategy:**

| Content | TTL |
|---|---|
| Thumbnails | 30 days |
| Full resolution | 7 days |
| Cold content (90+ days unaccessed) | Glacier |

---

### Q5 — Feed pagination

**Question:** How does your Feed Service paginate results as a user scrolls?

**Answer:**

Cursor-based pagination using `creationTime` or `postId` as the cursor.

```
First request:    GET /feed
Response:         posts[0..19] + cursor = postId of last post

Next request:     GET /feed?after=<cursor>
Feed Service:     Filter Feed Cache WHERE creationTime < cursor, LIMIT 20
Response:         posts[20..39] + new cursor
```

Feed Cache holds significantly more posts than one page. Posts already seen are tracked via the cursor — Feed Service filters them out during ranking.

**Why not offset pagination?**

`LIMIT 20 OFFSET 1000` requires the DB to scan and discard 1,000 rows on every request. At 51K feed loads/sec, this is catastrophically expensive. Cursor pagination starts exactly at the last seen position — O(1) regardless of scroll depth.

---

### Q6 — Search indexing

**Question:** How would you add search to this system?

**Answer:**

Two separate Elasticsearch indexes:

**User index** — optimized for prefix and exact match:
```json
{
  "username": "virat.kohli",
  "userId": "u_123",
  "displayName": "Virat Kohli"
}
```

**Post index** — optimized for full-text search with relevance ranking:
```json
{
  "postId": "p_456",
  "userId": "u_123",
  "content": "Just won the World Cup!",
  "createdAt": "2025-06-29T..."
}
```

**Indexing pipeline:**
```
Post DB write → DynamoDB Streams → Kafka → Elasticsearch Indexer → ES cluster
User DB write → CDC → Kafka → Elasticsearch Indexer → ES cluster
```

Indexing is async — slight lag between post creation and search visibility is acceptable (eventual consistency).

---

### Q7 — Unfollow consistency

**Question:** A user unfollows a celebrity. How quickly does that take effect in the feed? What's the worst case?

**Answer:**

```
User unfollows → Relationship Service
              → cache-first write to Relationship Cache (Redis)
              → async write to Relationship DB (DynamoDB)
```

On next feed load, Feed Service reads the updated Relationship Cache and excludes the unfollowed user's posts. Since celebrity posts are pulled at read time (not pre-computed via fanout), the exclusion is immediate as soon as the cache write propagates.

**Client-side fallback:** Maintain a short-lived in-memory list of recent user actions (follows/unfollows) in the client. Even if the cache write hasn't propagated yet, the client filters out posts from recently unfollowed accounts instantly.

**Worst case:** One feed refresh cycle — a few seconds if the cache write is slightly delayed.

---

### Q8 — Database choices

**Question:** Why DynamoDB for posts and not Cassandra or MongoDB?

**Answer:**

**Access pattern fit:** Posts are primarily accessed by `postId` (key-value lookup) or `userId + creationTime` (sorted scan). DynamoDB's partition key + sort key model maps perfectly to both.

**AWS ecosystem coherence:**
- DynamoDB Streams replaces the need for a separate CDC tool
- Native integration with Lambda, SQS, S3 — reduces operational surface
- SQS can replace Kafka for lower-complexity async workloads
- Global Tables provide multi-region replication with zero custom config

**Operational simplicity:** Zero infrastructure management vs Cassandra cluster tuning, compaction, repair, and topology management.

**What you give up vs Cassandra:**
- Less flexibility in complex query patterns (no secondary indexes at scale)
- Pay-per-read/write model can be expensive for scan-heavy workloads
- Cassandra gives more control over replication topology (e.g. multi-DC with custom consistency levels)

**Verdict:** DynamoDB is the right call in an AWS-native stack for Instagram's access patterns. Cassandra wins if you need fine-grained replication control or are cloud-agnostic.

---

### Q9 — Outbox pattern vs DynamoDB Streams

**Question:** Explain the transactional outbox pattern. How does it compare to DynamoDB Streams?

**Answer:**

**Outbox pattern:**
```
Post Service writes:
  1. Post record → Post DB       ┐ atomic
  2. Outbox record → Outbox DB   ┘ transaction

Relay/poller:
  - Continuously reads unprocessed outbox records
  - Publishes each to Kafka
  - Marks record as processed on ACK

On crash after DB write: outbox record survives, relay picks it up on recovery.
Guarantee: at-least-once delivery to Kafka.
```

**DynamoDB Streams:**
```
DynamoDB automatically captures every write as a change event.
A Kafka consumer reads from the stream and publishes to Kafka.
Built-in 24-hour retention + automatic retry.
No outbox table or relay needed.
```

| | Outbox | DynamoDB Streams |
|---|---|---|
| Retention | Unlimited | 24 hours |
| Infrastructure | Outbox table + relay | Zero |
| Portability | Any DB | DynamoDB only |
| Complexity | Medium | Low |

**When to use which:**
- AWS-native stack → DynamoDB Streams (simpler, zero extra infra)
- High durability requirements or multi-DB → Outbox pattern

---

## Quick-Reference: NFRs to State Upfront

Always state these **before touching the design** in an interview:

```
Feed load p99:          < 500ms
Post upload ACK:        < 200ms
Media load p95:         < 1s (CDN hit)
Like count consistency: Eventual, < 10s
Feed consistency:       Eventual, < 30s
Availability:           99.99%
Peak write QPS:         ~35K posts/s
Peak read QPS:          ~51K feed loads/s
```

---

## Important Keywords

Terms you must be able to define, use correctly in a sentence, and connect to a design decision. If an interviewer says one of these, you should immediately know which part of the system it maps to.

---

### Fanout & Feed

| Keyword | What it means in context |
|---|---|
| **Fanout on write** | Push post to all followers' Feed Cache at write time. Fast reads, expensive writes. Used for regular users. |
| **Fanout on read** | Pull posts at read time from the author's Post Cache. No write cost, but adds latency on read. Used for celebrities. |
| **Hybrid fanout** | Push for regular users, pull for celebrities. The correct answer for any social feed at scale. |
| **Celebrity problem** | A user with 50M+ followers triggers 50M fanout events per post — Kafka backpressure + worker overload. |
| **Celebrity registry** | A small dedicated table/cache updated async when a user crosses the follower threshold. Decouples detection from the write path. |
| **Feed hydration** | Taking a list of postIds from Feed Cache and enriching them with full post data (captions, media URLs, like counts). Done by Feed Service before returning to client. |
| **Feed ranking** | Ordering posts by relevance, not just time. Signals: engagement, relationship strength, recency. Done in Feed Service after hydration. |

---

### Caching

| Keyword | What it means in context |
|---|---|
| **Post Cache** | Redis cache of recent posts by postId. Used for celebrity pull path and post hydration. |
| **Feed Cache** | Redis cache of pre-computed feed (list of postIds) per user. Written by Fanout Workers, read by Feed Service. |
| **Relationship Cache** | Redis cache of each user's follow list, tagged `regular` or `celebrity`. Read on every feed load. |
| **User Cache** | Redis cache of user profiles and follower counts. Used for celebrity threshold checks. |
| **Cache-first write** | Write to cache before (or simultaneously with) DB. Ensures the next read sees the updated state immediately. Used for unfollow consistency. |
| **Single-flight fetch** | Request collapsing at CDN layer — only one upstream fetch fires for a cache miss regardless of how many concurrent requests arrive. Prevents thundering herd on S3. |
| **TTL (Time To Live)** | Expiry time on cache entries. Thumbnails: 30 days. Full-res media: 7 days. Short TTL for volatile data (feed). |

---

### Async & Queues

| Keyword | What it means in context |
|---|---|
| **DynamoDB Streams** | Built-in CDC (Change Data Capture) on DynamoDB. Captures every write as an ordered event stream. 24-hour retention. Used as the trigger from Post DB to Kafka. |
| **CDC (Change Data Capture)** | Capturing DB writes as events without polling. DynamoDB Streams, Debezium (Postgres), and the outbox pattern are all CDC approaches. |
| **Outbox pattern** | Write the domain record and an outbox event record atomically. A relay polls the outbox and publishes to Kafka. Guarantees at-least-once delivery even if the service crashes. Unlimited retention — unlike DynamoDB Streams. |
| **At-least-once delivery** | A message is delivered one or more times. Consumers must be idempotent to handle duplicates. |
| **Idempotency** | An operation that produces the same result no matter how many times it is applied. Likes are idempotent — liking twice = liked once. |
| **Backpressure** | When a consumer can't keep up with a producer, causing the queue to grow and eventually block. Celebrity fanout causes backpressure on Kafka. |
| **Fanout Workers** | Kafka consumers that read onPostCreated events and write postIds to each follower's Feed Cache and Feed DB. |
| **Tumbling window** | A fixed, non-overlapping time window in stream processing. Flink uses a 5-second tumbling window to aggregate like counts before writing to the counter DB. |

---

### Storage & Databases

| Keyword | What it means in context |
|---|---|
| **Hot partition** | A single DynamoDB partition receiving disproportionately high traffic. Viral post likes on `postId` alone = hot partition. Solved with composite shard key `postId:shardId`. |
| **Composite shard key** | Partition key combining two fields (e.g. `postId:shardId`) to distribute writes across multiple partitions. |
| **Scatter-gather** | Read from all shards and merge the results. Used to compute total like count across `postId:0` through `postId:99`. |
| **Cursor-based pagination** | Pagination using the last seen record's ID or timestamp as the cursor. O(1) regardless of page depth. Superior to offset pagination at scale. |
| **Offset pagination** | `LIMIT 20 OFFSET N` — requires scanning and discarding N rows. Expensive at scale. Never use for high-traffic feeds. |
| **Eventual consistency** | A guarantee that, given no new updates, all replicas will eventually converge to the same value. Feed and like counts are eventually consistent. |
| **Strong consistency** | Every read reflects the most recent write. Post durability uses strong consistency — a post must not be lost after 200 OK. |

---

### Media & CDN

| Keyword | What it means in context |
|---|---|
| **Pre-signed URL** | A time-limited URL generated by Post Service that allows the client to upload directly to S3. Offloads bandwidth from app servers. |
| **Transcoder** | Async service that generates multiple resolutions (thumbnail, medium, full) from the original upload. Triggered by Kafka event after post write. |
| **Manifest file** | A record in Post Cache/DB mapping a postId to all its resolution URLs. Client reads the manifest and selects the appropriate resolution. |
| **Adaptive bitrate** | Client-side logic to select video quality based on current network conditions. Implemented via HLS or DASH chunked video format. |
| **CDN (Content Delivery Network)** | Geographically distributed cache for static assets. Requests are routed to the nearest PoP (Point of Presence). Edge CDN → Global CDN → S3 on miss. |
| **Edge CDN** | The CDN node closest to the user (city/region level). Serves the majority of requests at ~20–50ms. |
| **GeoDNS** | Routes DNS queries to the nearest data centre or CDN PoP based on the user's IP geolocation. |
| **S3 Glacier** | Cold storage tier for infrequently accessed objects. Retrieval takes minutes. Used for media not accessed in 90+ days. |

---

### Architecture & Patterns

| Keyword | What it means in context |
|---|---|
| **Snowflake ID** | Twitter's distributed ID generation algorithm. Produces time-ordered 64-bit IDs without a central DB. Encodes timestamp + datacenter ID + sequence number. |
| **Elasticsearch inverted index** | A data structure mapping each word/token to the list of documents containing it. Enables full-text search. Used for post content search. |
| **Flink** | Apache Flink — a distributed stream processing engine. Used here for aggregating like counts with tumbling windows. Better than raw Kafka consumers for stateful aggregation. |
| **Intra-region replication** | DynamoDB automatically replicates data across multiple AZs within a region. Provides durability and availability within a single region. |
| **Global Tables (DynamoDB)** | Multi-region active-active replication for DynamoDB. Enables low-latency reads and writes from any region. |
| **Rate limiting** | Throttling requests at the API Gateway layer to protect downstream services. Prevents abuse and ensures fair usage. |
| **Idempotency key** | A unique key included in a request to ensure it is processed exactly once even if retried. Used in post creation to prevent duplicate posts on retry. |

---

## Concepts Checklist

- [ ] Hybrid fanout (push for regular, pull for celebrities)
- [ ] Celebrity registry pattern
- [ ] DynamoDB Streams as CDC replacement
- [ ] Outbox pattern — when to use vs Streams
- [ ] Pre-signed S3 URLs for media upload
- [ ] Single-flight fetch / request collapsing at CDN
- [ ] Cursor-based pagination vs offset
- [ ] Hot partition mitigation via composite shard keys
- [ ] Flink tumbling windows for like aggregation
- [ ] Adaptive bitrate + HLS/DASH for video
- [ ] Elasticsearch dual-index (user vs post)
- [ ] Cache-first write for unfollow consistency
- [ ] Snowflake ID generation
- [ ] CDN TTL strategy (thumbnail vs full-res vs cold)
- [ ] 200 OK timing — return after DB write, not after fanout