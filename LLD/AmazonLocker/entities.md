# Amazon Locker — Entities

---

## Enums
```
Size:         SMALL, MEDIUM, LARGE
LockerStatus: AVAILABLE, ASSIGNED, OCCUPIED, OUT_OF_SERVICE
OrderStatus:  ASSIGNED, PICKED_UP, STORED, EXPIRED, RETURNED
```

---

## interface FeeComputeStrategy                      // Strategy pattern
```
double computeFee(Order order)
```
**Implementation:** FixedRateFeeComputeStrategy(double fixedRate)
- returns `(today - orderPlacedAt) * fixedRate`
- applied at collection time — penalty for full window occupancy

---

## interface LockerFindingStrategy                   // Strategy pattern
```
Optional<Compartment> getLocker(List<Compartment> compartments, Size size)
```
**Implementation:** FixedSizeLockerFindingStrategy
- filters by exact size match + AVAILABLE status
- returns first match wrapped in Optional

---

## interface Observer                                // Observer pattern
```
void notifyOnUpdate(Order order)
```
**Implementation:** User
- switch on OrderStatus → prints appropriate message per state

---

## User implements Observer
```
String id, name, contact

void notifyOnUpdate(Order order)    // handles ASSIGNED (OTP), PICKED_UP, STORED, RETURNED
getters
```

---

## Compartment
```
String id
Size size
volatile LockerStatus lockerStatus  // volatile — status changes concurrently across threads

getters
void setLockerStatus(LockerStatus)
```

---

## Order
```
String id                           // UUID
String userId
LocalDateTime orderPlacedAt
LocalDateTime orderExpiresAt        // orderPlacedAt + 3 days
Double charge                       // null until collection, set at returnOrder
volatile OrderStatus orderStatus    // volatile — updated across threads
List<Observer> observers            // CopyOnWriteArrayList, user added at construction
String otp                          // UUID substring, generated at construction
Compartment compartment             // assigned at construction

// Internal flow methods — called by AmazonLocker
String assignOrder()                // status=ASSIGNED, notify observers, return OTP
void pickUpOrder()                  // status=PICKED_UP, notify observers (delivery collected from customer)
void storeOrder()                   // status=STORED, compartment→OCCUPIED, notify observers
void returnOrder(double fees)       // charge=fees, compartment→AVAILABLE, status=RETURNED, notify observers

getters
```

---

## AmazonLocker                                      // Singleton pattern
```
Map<String, Compartment> compartmentMap   // ConcurrentHashMap
Map<String, User> userMap                 // ConcurrentHashMap
Map<String, Order> orderMap               // ConcurrentHashMap
FeeComputeStrategy feeComputeStrategy     // default: FixedRateFeeComputeStrategy(2.0)
LockerFindingStrategy lockerFindingStrategy // default: FixedSizeLockerFindingStrategy

static getInstance()                      // double-checked locking

synchronized String placeOrder(String userId, Size size)
    // find user → find compartment (orElseThrow) → mark ASSIGNED
    // → create Order → store in orderMap → assignOrder() → return OTP
    // synchronized — prevents two orders grabbing the same compartment

void pickUpOrder(String orderId)          // delivery guy collected from customer
void storeOrder(String orderId)           // delivery guy placed in locker → OCCUPIED

void returnOrder(String userId, String orderId, String otp)
    // validates userId + OTP → computeFee → order.returnOrder(fees) → locker AVAILABLE
    // this is the customer collection step (confusingly named returnOrder)

List<Order> getAllOrdersForUser(String userId)
List<Order> getAllExpiredOrders()          // hook for cron job — finds orders past expiry
void markCompartmentOutOfService(Compartment compartment)
```

---

## Key Concurrency Notes
| Scenario | Solution |
|---|---|
| Two deliveries grabbing same locker | `synchronized` on `placeOrder` — atomic find + mark ASSIGNED |
| Locker status visibility | `volatile LockerStatus` on Compartment |
| Order status visibility | `volatile OrderStatus` on Order |
| Observer list | `CopyOnWriteArrayList` |
| All maps | `ConcurrentHashMap` |

---

## Flow Summary
- **Place:** AmazonLocker.placeOrder (synchronized) → find locker → ASSIGNED → create Order + OTP → notify user
- **Pickup:** pickUpOrder → delivery guy collected from customer → PICKED_UP
- **Store:** storeOrder → delivery guy placed in locker → OCCUPIED
- **Collect:** returnOrder (OTP validated) → compute fee → AVAILABLE → RETURNED → notify user
- **Expiry:** getAllExpiredOrders() (cron hook) → returnOrder without OTP for expired ones → charge + free locker
