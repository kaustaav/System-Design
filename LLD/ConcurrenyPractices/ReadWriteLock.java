package ConcurrenyPractices;

// Multiple readers can hold the lock simultaneously.
// A writer needs exclusive access — no active readers, no other writer.
public class ReadWriteLock {
    private int activeReaders = 0;
    private boolean writerActive = false;
    private final Object lock = new Object();

    public void readLock() throws InterruptedException {
        synchronized (lock) {
            while (writerActive)       // wait if a writer is active
                lock.wait();
            activeReaders++;
        }
    }

    public void readUnlock() {
        synchronized (lock) {
            activeReaders--;
            if (activeReaders == 0)
                lock.notifyAll();      // wake up any waiting writer
        }
    }

    public void writeLock() throws InterruptedException {
        synchronized (lock) {
            while (writerActive || activeReaders > 0) // wait for all readers + writers to finish
                lock.wait();
            writerActive = true;
        }
    }

    public void writeUnlock() {
        synchronized (lock) {
            writerActive = false;
            lock.notifyAll();          // wake up all waiting readers + writers
        }
    }

    // ── Demo ─────────────────────────────────────────────────────────────────
    // 4 readers run concurrently; writer waits for all readers then gets exclusive lock
    public static void main(String[] args) throws InterruptedException {
        System.out.println("═══ ReadWriteLock demo (4 readers + 1 writer) ═══");
        ReadWriteLock rwLock = new ReadWriteLock();
        final int[] sharedData = {0};

        // 4 readers — all start together, should overlap
        for (int i = 1; i <= 4; i++) {
            int id = i;
            new Thread(() -> {
                try {
                    rwLock.readLock();
                    System.out.println("  Reader-" + id + " reading: " + sharedData[0]);
                    Thread.sleep(300);
                    System.out.println("  Reader-" + id + " done");
                    rwLock.readUnlock();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }

        // Writer — starts slightly after readers, must wait for all of them
        new Thread(() -> {
            try {
                Thread.sleep(50);
                System.out.println("  Writer waiting for readers...");
                rwLock.writeLock();
                sharedData[0] = 42;
                System.out.println("  Writer wrote: " + sharedData[0] + " ← exclusive access");
                Thread.sleep(200);
                rwLock.writeUnlock();
                System.out.println("  Writer done");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
}
