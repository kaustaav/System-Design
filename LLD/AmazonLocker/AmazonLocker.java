package AmazonLocker;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

enum Size {
    SMALL, MEDIUM, LARGE
}

enum LockerStatus {
    ASSIGNED, OCCUPIED, AVAILABLE, OUT_OF_SERVICE
}

enum OrderStatus {
    ASSIGNED, PICKED_UP, STORED, EXPIRED, RETURNED
}

interface FeeComputeStrategy {
    public double computeFee(Order order);
}

class FixedRateFeeComputeStrategy implements FeeComputeStrategy {
    private final double fixedRate;

    public FixedRateFeeComputeStrategy(double fixedRate) {
        this.fixedRate = fixedRate;
    }

    @Override
    public double computeFee(Order order) {
        return (LocalDate.now().toEpochDay() - order.getOrderPlacedAt().toLocalDate().toEpochDay()) * fixedRate;
    }

    public double getFixedRate() {
        return fixedRate;
    }

}

interface LockerFindingStrategy {
    public Optional<Compartment> getLocker(List<Compartment> compartments, Size size);
}

class FixedSizeLockerFindingStrategy implements LockerFindingStrategy {
    @Override
    public Optional<Compartment> getLocker(List<Compartment> compartments, Size size) {
        return compartments.stream().filter(compartment -> compartment.getSize().equals(size))
                .filter(compartment -> compartment.getLockerStatus().equals(LockerStatus.AVAILABLE)).findFirst();
    }
}

interface Observer {
    public void notifyOnUpdate(Order order);
}

class User implements Observer {
    private final String id;
    private final String name;
    private final String contact;

    public User(String id, String name, String contact) {
        this.id = id;
        this.name = name;
        this.contact = contact;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getContact() {
        return contact;
    }

    @Override
    public void notifyOnUpdate(Order order) {
        switch (order.getOrderStatus()) {
            case OrderStatus.ASSIGNED:
                System.out.println("Order ID: " + order.getId() + " assigned successfully. OTP is " + order.getOtp());
                return;
            case OrderStatus.PICKED_UP:
                System.out.println("Item picked up successfully");
                return;
            case OrderStatus.STORED:
                System.out.println("Item stored successfully");
                return;
            case OrderStatus.RETURNED:
                System.out.println("Item has been returned to you");
                return;
            default:
                return;
        }
    }
}

class Order {
    private final String id;
    private final String userId;
    private final LocalDateTime orderPlacedAt;
    private final LocalDateTime orderExpiresAt;
    private Double charge;
    private volatile OrderStatus orderStatus;
    private final List<Observer> observers;
    private final String otp;
    private final Compartment compartment;

    public Order(User user, Compartment compartment) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.userId = user.getId();
        orderStatus = OrderStatus.ASSIGNED;
        this.orderPlacedAt = LocalDateTime.now();
        this.orderExpiresAt = orderPlacedAt.plusDays(3);
        this.observers = new CopyOnWriteArrayList<>();
        observers.add(user);
        otp = UUID.randomUUID().toString().substring(0, 6);
        this.compartment = compartment;
    }

    protected String assignOrder() {
        for (Observer observer : observers)
            observer.notifyOnUpdate(this);
        return otp;
    }

    protected void pickUpOrder() {
        orderStatus = OrderStatus.PICKED_UP;
        for (Observer observer : observers)
            observer.notifyOnUpdate(this);
    }

    protected void storeOrder() {
        orderStatus = OrderStatus.STORED;
        compartment.setLockerStatus(LockerStatus.OCCUPIED);
        for (Observer observer : observers)
            observer.notifyOnUpdate(this);
    }

    protected void returnOrder(double fees) {
        charge = fees;
        compartment.setLockerStatus(LockerStatus.AVAILABLE);
        orderStatus = OrderStatus.RETURNED;
        for (Observer observer : observers)
            observer.notifyOnUpdate(this);
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public LocalDateTime getOrderPlacedAt() {
        return orderPlacedAt;
    }

    public LocalDateTime getOrderExpiresAt() {
        return orderExpiresAt;
    }

    public Double getCharge() {
        return charge;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public List<Observer> getObservers() {
        return observers;
    }

    public String getOtp() {
        return otp;
    }

    public Compartment getCompartment() {
        return compartment;
    }

}

class Compartment {
    private final String id;
    private final Size size;
    private volatile LockerStatus lockerStatus;

    public Compartment(String id, Size size) {
        this.id = id;
        this.size = size;
        lockerStatus = LockerStatus.AVAILABLE;
    }

    public String getId() {
        return id;
    }

    public Size getSize() {
        return size;
    }

    public LockerStatus getLockerStatus() {
        return lockerStatus;
    }

    public void setLockerStatus(LockerStatus lockerStatus) {
        this.lockerStatus = lockerStatus;
    }
}

public class AmazonLocker {
    private static volatile AmazonLocker instance;
    private final Map<String, Compartment> compartmentMap;
    private final Map<String, User> userMap;
    private final Map<String, Order> orderMap;
    private FeeComputeStrategy feeComputeStrategy;
    private LockerFindingStrategy lockerFindingStrategy;

    private AmazonLocker() {
        compartmentMap = new ConcurrentHashMap<>();
        userMap = new ConcurrentHashMap<>();
        orderMap = new ConcurrentHashMap<>();
        feeComputeStrategy = new FixedRateFeeComputeStrategy(2.0);
        lockerFindingStrategy = new FixedSizeLockerFindingStrategy();
    }

    public static AmazonLocker getInstance() {
        if (instance == null)
            synchronized (AmazonLocker.class) {
                if (instance == null)
                    instance = new AmazonLocker();
            }
        return instance;
    }

    public synchronized String placeOrder(String userId, Size size) {
        User user = userMap.getOrDefault(userId, null);
        if (user == null)
            throw new IllegalStateException("User not found");
        Compartment compartment = lockerFindingStrategy.getLocker(List.copyOf(compartmentMap.values()), size)
                .orElseThrow();
        compartment.setLockerStatus(LockerStatus.ASSIGNED);
        Order order = new Order(user, compartment);
        orderMap.put(order.getId(), order);
        return order.assignOrder();
    }

    public void pickUpOrder(String orderId) {
        Order order = orderMap.getOrDefault(orderId, null);
        if (order == null)
            throw new IllegalStateException("Order not found");
        order.pickUpOrder();
    }

    public void storeOrder(String orderId) {
        Order order = orderMap.getOrDefault(orderId, null);
        if (order == null)
            throw new IllegalStateException("Order not found");
        order.storeOrder();
    }

    public void returnOrder(String userId, String orderId, String otp) {
        Order order = orderMap.getOrDefault(orderId, null);
        if (order == null)
            throw new IllegalStateException("Order not found");
        User user = userMap.getOrDefault(userId, null);
        if (user == null)
            throw new IllegalStateException("User not found");
        if (!order.getUserId().equals(userId) || !order.getOtp().equals(otp))
            throw new IllegalStateException("Not authorised");
        double fees = feeComputeStrategy.computeFee(order);
        order.returnOrder(fees);
    }

    public List<Order> getAllOrdersForUser(String userId) {
        User user = userMap.getOrDefault(userId, null);
        if (user == null)
            throw new IllegalStateException("User doesn't exist");
        return orderMap.values().stream().filter(order -> order.getUserId().equals(user.getId())).toList();
    }

    public void markCompartmentOutOfServie(Compartment compartment) {
    }

    public List<Order> getAllExpireOrders() {
        return orderMap.values().stream().filter(order -> order.getOrderExpiresAt().isBefore(LocalDateTime.now()))
                .toList();
    }

}
