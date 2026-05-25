package ConcurrenyPractices;

public class Semaphore {
    private int permits;
    private final Object lock = new Object();

    public Semaphore(int permits) {
        if (permits <= 0)
            throw new IllegalArgumentException("Permits must be positive");
        this.permits = permits;
    }

    public void acquire() throws InterruptedException {
        synchronized (lock) {
            while (permits == 0) {
                lock.wait();
            }
            permits--;
        }
    }

    public void release() {
        synchronized (lock) {
            permits++;
            lock.notify();
        }
    }

    public boolean tryAcquire() {
        synchronized (lock) {
            if (permits > 0) {
                permits--;
                return true;
            }
            return false;
        }
    }

    public int getAvailablePermits() {
        synchronized (lock) {
            return permits;
        }
    }

    // ── Demo ─────────────────────────────────────────────────────────────────
    // 2 permits, 5 threads — at most 2 threads inside the critical section at once
    public static void main(String[] args) throws InterruptedException {
        System.out.println("═══ Semaphore demo (2 permits, 5 threads) ═══");
        Semaphore semaphore = new Semaphore(2);

        for (int i = 1; i <= 5; i++) {
            int id = i;
            new Thread(() -> {
                try {
                    semaphore.acquire();
                    System.out.println("  Thread-" + id + " acquired  | permits left: " + semaphore.getAvailablePermits());
                    Thread.sleep(300);
                    System.out.println("  Thread-" + id + " releasing");
                    semaphore.release();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
    }
}
