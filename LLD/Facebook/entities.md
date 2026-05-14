# Facebook LLD Review Sheet

## 1. Core Implemented Entities & Services

**User**
- **Attributes:**
  - `userId`
  - `userName`

**Post**
- **Attributes:**
  - `postId`
  - `content`
  - `author`
  - `creationTime`
  - `likes`
  - `comments`
- **Methods:**
  - `addLike(User)`
  - `addComment(User, Comment)`

**Comment**
- **Attributes:**
  - `commentId`
  - `postId`
  - `commentedBy`
  - `content`
  - `timestamp`

**UserRepo**
- **Attributes:**
  - `instance`
  - `userMap`
- **Methods:**
  - `addUser(User)`
  - `getUserById(String)`

**PostRepo**
- **Attributes:**
  - `instance`
  - `postsMap`
- **Methods:**
  - `createPost(Post)`
  - `getPostsByUser(String)`
  - `getPostsByUser(String, Integer)`

**RelationshipRepo**
- **Attributes:**
  - `instance`
  - `followersMap`
  - `followeesMap`
- **Methods:**
  - `followUser(String, String)`
  - `getFollowersOfUser(String)`
  - `getFolloweesOfUser(String)`

**FeedGenerationStrategy (Interface)**
- **Methods:**
  - `getFeedForUser(String)`

**ChronologicalFeedGenerationStrategy**
- **Attributes:**
  - `userService`
  - `postService`
- **Methods:**
  - `getFeedForUser(String)`

**NewsFeedService**
- **Attributes:**
  - `feedGenerationStrategy`
- **Methods:**
  - `getFeedForUser(User)`

**UserService**
- **Attributes:**
  - `userRepo`
- **Methods:**
  - `createUser(User)`
  - `getUserById(String)`

**PostService**
- **Attributes:**
  - `postRepo`
- **Methods:**
  - `createPost(Post)`
  - `getPostsByUser(String, Integer)`
  - `addLike(Post, User)`
  - `addComment(Post, User, Comment)`

**RelationshipService**
- **Attributes:**
  - `relationshipRepo`
- **Methods:**
  - `followUser(String, String)`
  - `getFollowersOfUser(String)`
  - `getFolloweesOfUser(String)`

**Facebook (Singleton Facade)**
- **Attributes:**
  - `instance`
  - `userService`
  - `postService`
  - `relationshipService`
  - `newsFeedService`
- **Methods:**
  - `createUser(String, String)`
  - `createPost(String, String, User)`
  - `followUser(String, String)`
  - `addLike(User, Post)`
  - `addComment(User, Post, Comment)`
  - `getFeedForUser(User)`
  - `getPostForUser(User)`

---

## 2. Unanswered / To-Revisit Questions

- **Observer Pattern (Notifications):** Study the full structure — `Observable` interface (implemented by `Post`), `Observer` interface (implemented by `NotificationService`), `Event` object carrying postId/actorId/type, and `Post` holding a `List<Observer>` calling `observer.onEvent(event)`. Be able to draw this end-to-end.
- **Observer — Subject clarity:** Be ready to answer "who maintains the observer list and why" without hesitation. Answer: `Post` holds it because `Post` is the entity that knows when an event occurs.
- **Async notifications via Kafka:** Producer publishes `LikeEvent`/`CommentEvent`. Consumer writes to DynamoDB (like counter/comment store). DynamoDB Streams triggers notification fanout service — this ensures notification only fires after confirmed write. Idempotency key: `postId:userId` (drop timestamp). Redis cache checks key before processing; if exists, skip. TTL on key. Bloom filter avoided due to false positives.
- **Cache invalidation:** "How do you handle cache invalidation when a post is edited or deleted?" — No answer prepared yet.

---

## 3. Deep-Dive Discussion Points (To Secure L4/L5)
*(Use these keywords and architectural concepts verbally when discussing how to scale your implementation from an interview prototype to production).*

### Keyword/Concept: High-Write Concurrency (Atomic Operations)
- **The limitation of naive implementations:** Using `ArrayList` throws `ConcurrentModificationException` under load. Using `CopyOnWriteArrayList` destroys CPU during a viral post because it clones the entire array on every single comment.
- **The Production Fix:** Use a **`ConcurrentLinkedQueue`** (which you did!) to utilize lock-free Compare-And-Swap (CAS) algorithms, ensuring maximum throughput for viral posts.

### Keyword/Concept: Out-Of-Memory (OOM) Protection & Pagination
- **The limitation of naive implementations:** Fetching `getPostsByUser` pulls thousands of objects into heap memory all at once.
- **The Production Fix:** First, apply an API `threshold` bounds at the Service/Repo level (using `Math.min` or Streams `.limit()`) as done in the code to restrict payload sizes. In a real Database, upgrade this to **Cursor-based Pagination** to avoid slow SQL `OFFSET` performance during deep scrolling.

### Keyword/Concept: Hybrid Fan-Out for Feed Generation
- **The limitation of naive implementations:** The current `ChronologicalFeedGenerationStrategy` uses a pure "Pull" approach, executing expensive merges on the fly when the user opens the app.
- **The Production Fix:** Shift to a **Hybrid Fan-Out** model:
  1. Active Users: Use **Fan-out on Write (Push)** by storing pre-computed feeds in Redis.
  2. Inactive Users: Use **Fan-out on Read (Pull)** to save memory.
  3. Celebrity Users: Keep their posts out of the Push queues to prevent the Thundering Herd bottleneck; merge them manually at read time.

### Keyword/Concept: Eventual Consistency for Interactions
- **The limitation of naive implementations:** `addLike` synchronously updates the main datastore object, creating massive lock contention in a relational database for a viral post.
- **The Production Fix:** Push likes and comments into an asynchronous **Message Queue (Kafka)**. Maintain the raw interaction log independently, and calculate an aggregate "Like Count" decoupled from the primary write-path, serving users an **Eventually Consistent** snapshot.

### Keyword/Concept: Request Collapsing (Single Flight Fetch)
- **The limitation of naive implementations:** An un-cached viral post requested by 10,000 users at exactly the same millisecond will hit the database 10,000 times simultaneously (Cache Stampede).
- **The Production Fix:** Apply **Single Flight Fetch**. Block 9,999 of those threads temporarily, let exactly 1 thread query the DB, populate the cache, and then fan the single result back out to all 10,000 waiting threads.