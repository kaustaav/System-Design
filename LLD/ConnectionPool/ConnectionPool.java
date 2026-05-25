package ConnectionPool;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

interface Connection {
    String getId();

    void executeQuery(String query);

    boolean isValid();

    void close();
}

class JDBCConnection implements Connection {
    private final String id;
    private volatile boolean valid;

    public JDBCConnection() {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.valid = true;
        System.out.println("[Pool] Created connection: " + id);
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void executeQuery(String query) {
        if (!valid)
            throw new IllegalStateException("Connection " + id + " is closed");
        System.out.println("[" + id + "] Executing: " + query);
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    @Override
    public void close() {
        valid = false;
        System.out.println("[Pool] Closed connection: " + id);
    }
}

interface ConnectionFactory {
    Connection createConnection();
}

class JDBCConnectionFactory implements ConnectionFactory {
    private final String url;
    private final String username;

    public JDBCConnectionFactory(String url, String username) {
        this.url = url;
        this.username = username;
    }

    @Override
    public Connection createConnection() {
        // In real life: DriverManager.getConnection(url, username, password)
        System.out.println("[Factory] Connecting to " + url + " as " + username);
        return new JDBCConnection();
    }
}

public class ConnectionPool {
    private static volatile ConnectionPool instance;

    private final BlockingQueue<Connection> pool;
    private final ConnectionFactory factory;
    private final int maxPoolSize;
    private final AtomicInteger currentSize;
    private volatile boolean isShutdown;

    private ConnectionPool(ConnectionFactory factory, int minPoolSize, int maxPoolSize) {
        this.factory = factory;
        this.maxPoolSize = maxPoolSize;
        this.pool = new ArrayBlockingQueue<>(maxPoolSize);
        this.currentSize = new AtomicInteger(0);
        this.isShutdown = false;
        for (int i = 0; i < minPoolSize; i++) {
            pool.offer(factory.createConnection());
            currentSize.incrementAndGet();
        }
    }

    public static void initialize(ConnectionFactory factory, int minPoolSize, int maxPoolSize) {
        if (instance == null)
            synchronized (ConnectionPool.class) {
                if (instance == null)
                    instance = new ConnectionPool(factory, minPoolSize, maxPoolSize);
            }
    }

    public static ConnectionPool getInstance() {
        if (instance == null)
            throw new IllegalStateException("ConnectionPool not initialized. Call initialize() first.");
        return instance;
    }

    public Connection acquire(long timeout, TimeUnit unit) throws InterruptedException {
        if (isShutdown)
            throw new IllegalStateException("Connection pool is shut down");

        // 1. Non-blocking try — pick up an idle connection immediately
        Connection conn = pool.poll();
        if (conn != null)
            return validateOrReplace(conn);

        // 2. Pool empty — grow if below max (CAS ensures only one thread increments per
        // slot)
        int current;
        do {
            current = currentSize.get();
            if (current >= maxPoolSize)
                break;
        } while (!currentSize.compareAndSet(current, current + 1));

        if (current < maxPoolSize)
            return factory.createConnection(); // brand-new connection handed directly to caller

        // 3. At max capacity — wait for someone to release
        conn = pool.poll(timeout, unit);
        if (conn == null)
            throw new RuntimeException("Timed out waiting for a connection from the pool");
        return validateOrReplace(conn);
    }

    private Connection validateOrReplace(Connection conn) {
        if (conn.isValid())
            return conn;
        return factory.createConnection();
    }

    public void release(Connection connection) {
        if (connection == null)
            return;
        if (isShutdown) {
            connection.close();
            return;
        }
        if (!pool.offer(connection)) {
            // Queue full — more connections were created than the pool can hold
            connection.close();
            currentSize.decrementAndGet();
        }
    }

    public void shutdown() {
        isShutdown = true;
        Connection conn;
        while ((conn = pool.poll()) != null)
            conn.close();
    }
}
