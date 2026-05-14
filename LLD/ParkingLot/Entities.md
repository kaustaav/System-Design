# Parking Lot — Entities

---

## Enums
```
SpotType: SMALL, MEDIUM, LARGE
```

---

## abstract Vehicle
```
String licensePlate
SpotType spotType

getLicensePlate()
getSpotType()
```

### Bike extends Vehicle → SpotType.SMALL
### Car extends Vehicle  → SpotType.MEDIUM
### Truck extends Vehicle → SpotType.LARGE

---

## ParkingSpot
```
String spotId
Vehicle parkedVehicle          // null when available
SpotType spotType

boolean isAvailable()          // parkedVehicle == null
boolean canFitVehicle(SpotType) // exact SpotType match

synchronized void parkVehicle(Vehicle)   // throws if occupied or wrong type
synchronized void unparkVehicle()        // sets parkedVehicle = null
getters
```

---

## ParkingTicket
```
String ticketId
Vehicle vehicle
ParkingSpot parkingSpot
LocalDateTime entryTime        // set at construction
LocalDateTime exitTime         // null until exit, set by ParkingManager

setExitTime(LocalDateTime)
getters
```

---

## ParkingFloor
```
Integer floorNumber
Map<SpotType, List<ParkingSpot>> parkingSpotMap   // built from spotCountMap at construction

String getSpotId(SpotType, Integer count)          // "floor-S-0", "1-M-2" etc.
ParkingSpot getAvailableSpot(SpotType)             // linear scan (not used by ParkingManager)
Map<SpotType, List<ParkingSpot>> getParkingSpotMap()
List<ParkingSpot> getParkingSpotsForType(SpotType)
int getFloorNumber()
```

---

## interface FeesStrategy                            // Strategy pattern
```
double getFees(ParkingTicket ticket)
```
**Implementation:** FixedRateStrategy(double fixedRate)
- returns `fixedRate` regardless of duration or vehicle type

---

## ParkingManager
```
FeesStrategy feesStrategy
Map<String, ParkingTicket> activeTickets          // ConcurrentHashMap, keyed by ticketId
Map<SpotType, Queue<ParkingSpot>> availableSpotsMap  // ConcurrentHashMap of ConcurrentLinkedQueue
List<ParkingFloor> parkingFloors

// Constructor: populates availableSpotsMap from all floors × all spot types

ParkingTicket parkVehicle(Vehicle vehicle)
    // poll spot from ConcurrentLinkedQueue (lock-free) → synchronized spot.parkVehicle
    // retry loop: if spot was grabbed concurrently → poll again
    // throws IllegalStateException if queue is empty (lot full)
    // creates ParkingTicket → stores in activeTickets → returns ticket

double unparkVehicle(String ticketId)
    // lookup ticket → setExitTime → feesStrategy.getFees → spot.unparkVehicle
    // remove from activeTickets → re-enqueue spot → return fees

void setFeesStrategy(FeesStrategy)
void addFloor(ParkingFloor)
```

---

## Key Concurrency Notes
| Scenario | Solution |
|---|---|
| Two vehicles racing for the same spot | `synchronized` on `ParkingSpot.parkVehicle` — only one thread wins; loser retries |
| Lock-free spot allocation | `ConcurrentLinkedQueue.poll()` — atomic dequeue, no lock on the manager |
| Stale spot in queue (concurrent race) | Retry loop in `parkVehicle` — polls next spot if `IllegalStateException` thrown |
| Active ticket map | `ConcurrentHashMap` |

---

## Flow Summary
- **Park:** ParkingManager.parkVehicle → poll spot from queue (lock-free) → synchronized ParkingSpot.parkVehicle (retry on race) → create ticket → store in activeTickets
- **Unpark:** ParkingManager.unparkVehicle → lookup ticket → setExitTime → compute fees → unparkVehicle → remove ticket → re-enqueue spot