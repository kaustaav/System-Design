package ConcurrenyPractices;

import java.util.LinkedList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class BlockingQueue<T> {
    private final int capacity;
    private final LinkedList<T> queue = new LinkedList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notFull = lock.newCondition();
    private final Condition notEmpty = lock.newCondition();

    public BlockingQueue(int capacity) {
        this.capacity = capacity;
    }

    public void put(T item) throws InterruptedException {
        lock.lock();
        try {
            while (queue.size() == capacity)
                notFull.await();
            queue.add(item);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }

    }

    public T take() throws InterruptedException {
        lock.lock();
        try {
            while (queue.isEmpty())
                notEmpty.await();
            T item = queue.poll();
            notFull.signal();
            return item;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    // ── Demo ─────────────────────────────────────────────────────────────────
    // 1 producer, 2 consumers — producer blocks when queue full; consumers block when empty
    public static void main(String[] args) throws InterruptedException {
        System.out.println("═══ BlockingQueue demo (capacity=3, 1 producer, 2 consumers) ═══");
        BlockingQueue<Integer> queue = new BlockingQueue<>(3);

        Thread producer = new Thread(() -> {
            try {
                for (int i = 1; i <= 6; i++) {
                    queue.put(i);
                    System.out.println("  Producer put: " + i + "  | size: " + queue.size());
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread consumer1 = new Thread(() -> {
            try {
                for (int i = 0; i < 3; i++) {
                    Thread.sleep(250); // slower than producer → queue fills up
                    System.out.println("  Consumer-1 took: " + queue.take() + " | size: " + queue.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread consumer2 = new Thread(() -> {
            try {
                for (int i = 0; i < 3; i++) {
                    Thread.sleep(300);
                    System.out.println("  Consumer-2 took: " + queue.take() + " | size: " + queue.size());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        producer.start(); consumer1.start(); consumer2.start();
        producer.join();  consumer1.join();  consumer2.join();
        System.out.println("  → Queue drained. Final size: " + queue.size());
    }
}
