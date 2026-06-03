package ConcurrenyPractices;

import java.util.LinkedList;

public class BlockingQueue<T> {
    private final int capacity;
    private final LinkedList<T> queue = new LinkedList<>();
    private final Object lock = new Object();

    public BlockingQueue(int capacity) {
        this.capacity = capacity;
    }

    public void put(T item) throws InterruptedException {
        synchronized (lock) {
            while (queue.size() == capacity)
                lock.wait();
            queue.add(item);
            lock.notifyAll();
        }
    }

    public T take() throws InterruptedException {
        synchronized (lock) {
            while (queue.isEmpty())
                lock.wait();
            T item = queue.poll();
            lock.notifyAll();
            return item;
        }
    }

    public int size() {
        synchronized (lock) {
            return queue.size();
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
                    Thread.sleep(250);
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
