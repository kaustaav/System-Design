package JobScheduler;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

// ─── Enums ────────────────────────────────────────────────────────────────────

enum JobStatus {
    PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
}

// ─── ScheduleStrategy — Strategy pattern ─────────────────────────────────────

interface ScheduleStrategy {
    long nextRunTime(long scheduledAt, long completedAt, long intervalMs);
    boolean isRecurring();
}

class OneTimeStrategy implements ScheduleStrategy {
    @Override
    public long nextRunTime(long scheduledAt, long completedAt, long intervalMs) {
        return -1; // never runs again
    }

    @Override
    public boolean isRecurring() { return false; }
}

// Fires every intervalMs from when it was *scheduled* to run — ignores execution duration
class FixedRateStrategy implements ScheduleStrategy {
    @Override
    public long nextRunTime(long scheduledAt, long completedAt, long intervalMs) {
        return scheduledAt + intervalMs;
    }

    @Override
    public boolean isRecurring() { return true; }
}

// Fires every intervalMs from when the previous run *completed* — gap is always >= interval
class FixedDelayStrategy implements ScheduleStrategy {
    @Override
    public long nextRunTime(long scheduledAt, long completedAt, long intervalMs) {
        return completedAt + intervalMs;
    }

    @Override
    public boolean isRecurring() { return true; }
}

// ─── Job — Builder pattern + Delayed ─────────────────────────────────────────

class Job implements Delayed {
    private final String id;
    private final String name;
    private final Runnable task;
    private final long intervalMs;
    private final ScheduleStrategy scheduleStrategy;
    private volatile long nextRunTime;
    private volatile JobStatus status;

    private Job(Builder builder) {
        this.id               = builder.id;
        this.name             = builder.name;
        this.task             = builder.task;
        this.intervalMs       = builder.intervalMs;
        this.scheduleStrategy = builder.scheduleStrategy;
        this.nextRunTime      = builder.nextRunTime;
        this.status           = JobStatus.PENDING;
    }

    // Delayed interface — how long until this job is due
    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(nextRunTime - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
        return Long.compare(this.nextRunTime, ((Job) other).nextRunTime);
    }

    public String getId()                            { return id; }
    public String getName()                          { return name; }
    public Runnable getTask()                        { return task; }
    public long getIntervalMs()                      { return intervalMs; }
    public ScheduleStrategy getScheduleStrategy()    { return scheduleStrategy; }
    public long getNextRunTime()                     { return nextRunTime; }
    public void setNextRunTime(long nextRunTime)     { this.nextRunTime = nextRunTime; }
    public JobStatus getStatus()                     { return status; }
    public void setStatus(JobStatus status)          { this.status = status; }

    public static class Builder {
        private final String id = UUID.randomUUID().toString().substring(0, 8);
        private String name             = "unnamed-job";
        private Runnable task;
        private long intervalMs         = 0;
        private ScheduleStrategy scheduleStrategy = new OneTimeStrategy();
        private long nextRunTime        = System.currentTimeMillis(); // run immediately by default

        public Builder name(String name)                            { this.name = name; return this; }
        public Builder task(Runnable task)                          { this.task = task; return this; }
        public Builder intervalMs(long intervalMs)                  { this.intervalMs = intervalMs; return this; }
        public Builder scheduleStrategy(ScheduleStrategy strategy)  { this.scheduleStrategy = strategy; return this; }
        public Builder runAt(long epochMs)                          { this.nextRunTime = epochMs; return this; }
        public Builder runAfterMs(long delayMs)                     { this.nextRunTime = System.currentTimeMillis() + delayMs; return this; }

        public Job build() {
            if (task == null) throw new IllegalStateException("Job task must be set");
            return new Job(this);
        }
    }
}

// ─── JobScheduler — Singleton ─────────────────────────────────────────────────

public class JobScheduler {
    private static volatile JobScheduler instance;

    private final DelayQueue<Job> jobQueue;
    private final ExecutorService executor;
    private final Map<String, Job> jobMap;
    private final Thread schedulerThread;
    private final AtomicBoolean running;

    private JobScheduler(int threadPoolSize) {
        jobQueue        = new DelayQueue<>();
        executor        = Executors.newFixedThreadPool(threadPoolSize);
        jobMap          = new ConcurrentHashMap<>();
        running         = new AtomicBoolean(true);
        schedulerThread = new Thread(this::schedulerLoop, "job-scheduler-thread");
        schedulerThread.setDaemon(true);
        schedulerThread.start();
    }

    public static void initialize(int threadPoolSize) {
        if (instance == null)
            synchronized (JobScheduler.class) {
                if (instance == null)
                    instance = new JobScheduler(threadPoolSize);
            }
    }

    public static JobScheduler getInstance() {
        if (instance == null)
            throw new IllegalStateException("JobScheduler not initialized. Call initialize() first.");
        return instance;
    }

    // ── Scheduler loop — single thread, blocks on DelayQueue.take() ──────────
    private void schedulerLoop() {
        while (running.get()) {
            try {
                Job job = jobQueue.take(); // blocks until the next job is due

                if (job.getStatus() == JobStatus.CANCELLED) continue;

                executor.submit(() -> {
                    long scheduledAt = job.getNextRunTime();
                    job.setStatus(JobStatus.RUNNING);
                    System.out.println("[Scheduler] Running:   " + job.getName());

                    try {
                        job.getTask().run();
                        job.setStatus(JobStatus.COMPLETED);
                        System.out.println("[Scheduler] Completed: " + job.getName());
                    } catch (Exception e) {
                        job.setStatus(JobStatus.FAILED);
                        System.err.println("[Scheduler] Failed:    " + job.getName() + " — " + e.getMessage());
                    }

                    // Re-queue if recurring and not cancelled
                    if (job.getScheduleStrategy().isRecurring() && job.getStatus() != JobStatus.CANCELLED) {
                        long completedAt = System.currentTimeMillis();
                        job.setNextRunTime(
                                job.getScheduleStrategy().nextRunTime(scheduledAt, completedAt, job.getIntervalMs()));
                        job.setStatus(JobStatus.PENDING);
                        jobQueue.offer(job);
                    }
                });

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public Job schedule(Job job) {
        jobMap.put(job.getId(), job);
        jobQueue.offer(job);
        System.out.println("[Scheduler] Scheduled: " + job.getName() + " (id=" + job.getId() + ")");
        return job;
    }

    // Cancellation is lazy — job is skipped when the scheduler picks it up next
    public void cancel(String jobId) {
        Job job = jobMap.get(jobId);
        if (job == null) throw new IllegalArgumentException("Job not found: " + jobId);
        job.setStatus(JobStatus.CANCELLED);
        System.out.println("[Scheduler] Cancelled: " + job.getName());
    }

    public void shutdown() {
        running.set(false);
        schedulerThread.interrupt();
        executor.shutdown();
        System.out.println("[Scheduler] Shut down.");
    }

    // ── Demo ─────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws InterruptedException {
        JobScheduler.initialize(3);
        JobScheduler scheduler = JobScheduler.getInstance();

        // One-time job — runs once after 500ms
        scheduler.schedule(new Job.Builder()
                .name("one-time-job")
                .task(() -> System.out.println("  → one-time-job executed"))
                .runAfterMs(500)
                .scheduleStrategy(new OneTimeStrategy())
                .build());

        // Fixed-rate job — runs every 1s regardless of how long it takes
        scheduler.schedule(new Job.Builder()
                .name("fixed-rate-job")
                .task(() -> System.out.println("  → fixed-rate-job executed"))
                .runAfterMs(200)
                .intervalMs(1000)
                .scheduleStrategy(new FixedRateStrategy())
                .build());

        // Fixed-delay job — next run starts 800ms after this one finishes
        Job fixedDelayJob = scheduler.schedule(new Job.Builder()
                .name("fixed-delay-job")
                .task(() -> {
                    System.out.println("  → fixed-delay-job start");
                    try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    System.out.println("  → fixed-delay-job end");
                })
                .runAfterMs(300)
                .intervalMs(800)
                .scheduleStrategy(new FixedDelayStrategy())
                .build());

        Thread.sleep(3500);

        scheduler.cancel(fixedDelayJob.getId());

        Thread.sleep(1500);
        scheduler.shutdown();
    }
}
