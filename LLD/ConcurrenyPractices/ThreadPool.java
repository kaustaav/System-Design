package ConcurrenyPractices;

// ── Task interface ────────────────────────────────────────────────────────────

interface Task {
    void execute() throws Exception;
}

// ── ThreadPool
// ────────────────────────────────────────────────────────────────

public class ThreadPool {

    // Sentinel — a dummy Task placed in the queue to tell a worker to stop
    private static final Task POISON_PILL = () -> {
    };

    private final BlockingQueue<Task> taskQueue;
    private final Thread[] workers;

    public ThreadPool(int poolSize, int queueCapacity) {
        taskQueue = new BlockingQueue<>(queueCapacity);
        workers = new Thread[poolSize];
        for (int i = 0; i < poolSize; i++) {
            workers[i] = new Thread(() -> {
                try {
                    while (true) {
                        Task task = taskQueue.take();
                        if (task == POISON_PILL)
                            break; // shutdown signal received — exit
                        try {
                            task.execute();
                        } catch (Exception e) {
                            System.err.println("[worker] Task failed: " + e.getMessage() + " — continuing");
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            workers[i].start();
        }
    }

    public void submit(Task task) throws InterruptedException {
        taskQueue.put(task);
    }

    // One pill per worker — each worker exits when it dequeues its pill
    public void shutdown() throws InterruptedException {
        for (int i = 0; i < workers.length; i++)
            taskQueue.put(POISON_PILL);
        for (Thread worker : workers)
            worker.join();
    }

    // ── Demo ─────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws InterruptedException {
        ThreadPool pool = new ThreadPool(3, 5);

        for (int i = 1; i <= 6; i++) {
            int id = i;
            pool.submit(() -> {
                System.out.println("  [task-" + id + "] on " + Thread.currentThread().getName());
                Thread.sleep(200);
            });
        }

        pool.submit(() -> {
            throw new RuntimeException("intentional failure");
        });
        pool.submit(() -> System.out.println("  [recovery-task] worker survived ✓"));

        pool.shutdown();
        System.out.println("All done.");
    }
}
