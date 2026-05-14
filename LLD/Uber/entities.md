# Uber (Ride Sharing) — Entities

---

## Enums
```
DriverStatus: ONLINE, OFFLINE, ON_TRIP
TripStatus:   REQUESTED, ASSIGNED, STARTED, COMPLETED, CANCELLED
RideType:     BIKE, NON_AC_CAB, AC_CAB
```

---

## Location
```
double lat, lng

double getDistance(Location destination)    // Euclidean distance
String toString()
```

---

## Vehicle
```
String licenseNo, model
RideType rideType
getters
```

---

## interface TripObserver                             // Observer pattern
```
void onUpdate(Trip trip)
```
Implemented by: `Rider`, `Driver` (both extend `User`)
- Rider: prints trip status update
- Driver: prints new ride request (REQUESTED) or cancellation (CANCELLED)

---

## abstract User implements TripObserver
```
String id                                    // UUID
String name, contact
List<Trip> tripHistory                       // CopyOnWriteArrayList

void addTripToHistory(Trip trip)
abstract void onUpdate(Trip trip)
getters
```

### Rider extends User
```
void onUpdate(Trip trip)                     // notified on all state changes
```

### Driver extends User
```
Vehicle vehicle
Location currentLocation
DriverStatus driverStatus                    // starts OFFLINE

void onUpdate(Trip trip)                     // notified on REQUESTED (new ride) and CANCELLED
getters + setters for vehicle, currentLocation, driverStatus
```

---

## interface FareCalculationStrategy                 // Strategy pattern
```
double calculateFare(Location source, Location destination, RideType rideType)
```
**Implementation:** FixedRateFareCalculationStrategy(double fixedRate)
- returns `distance * fixedRate`

---

## interface DriverFindingStrategy                   // Strategy pattern
```
List<Driver> getDrivers(List<Driver> drivers, Location source, RideType rideType)
```
**Implementation:** NearestDriverStrategy(double thresholdRadius)
- filters: ONLINE, matching RideType, within thresholdRadius
- sorts: by distance from source ascending

---

## interface TripState                               // State pattern
```
void requestTrip(Trip trip)
void assignTrip(Trip trip, Driver driver)
void startTrip(Trip trip)
void endTrip(Trip trip)
void rejectTrip(Trip trip)
void cancelTrip(Trip trip)
```

### State Transition Table
| State | requestTrip | assignTrip | startTrip | endTrip | cancelTrip |
|---|---|---|---|---|---|
| RequestedTripState | throws | ✓ assign driver, ON_TRIP, → ASSIGNED, notify | throws | throws | ✓ → CANCELLED |
| AssignedTripState | throws | throws | ✓ → STARTED | throws | ✓ → CANCELLED |
| StartedTripState | throws | throws | throws | ✓ → COMPLETED, driver ONLINE, add history | throws |
| CompletedTripState | all throw | | | | |
| CancelledTripState | all throw | | | | |

---

## Trip                                              // Builder pattern
```
String id                                    // UUID
Rider rider
Driver driver                                // set on accept
Location source, destination
volatile TripState currentTripState          // volatile — visible across threads
volatile TripStatus tripStatus               // updated in sync with state transitions
double fare
List<TripObserver> tripObservers             // CopyOnWriteArrayList
Integer riderRating, driverRating            // nullable

// synchronized methods — prevent two drivers accepting same trip
synchronized void assignTrip(Driver driver)  → currentTripState.assignTrip(this, driver)
synchronized void startTrip()                → currentTripState.startTrip(this)
synchronized void endTrip()                  → currentTripState.endTrip(this)
synchronized void rejectTrip()               → currentTripState.rejectTrip(this)
synchronized void cancelTrip()               → currentTripState.cancelTrip(this)

void addObserver(TripObserver observer)
void removeObserver(TripObserver observer)
setDriver, setCurrentTripState, setTripStatus, setRiderRating, setDriverRating
getters
```

### TripBuilder (inner static class)
```
TripBuilder withRider(Rider)
TripBuilder withSource(Location)
TripBuilder withDestination(Location)
TripBuilder withFare(double)
Trip build()
```

---

## Uber                                              // Singleton pattern
```
Map<String, Rider> riderMap                  // ConcurrentHashMap
Map<String, Driver> driverMap                // ConcurrentHashMap
Map<String, Trip> activeTrips                // ConcurrentHashMap, keyed by tripId
DriverFindingStrategy driverFindingStrategy  // default: NearestDriverStrategy(radius=3)
FareCalculationStrategy fareCalculationStrategy // default: FixedRateFareCalculationStrategy(0.5)

static getInstance()                         // double-checked locking

void bookRide(Rider, RideType, Location src, Location dest)
    // calculate fare → build trip → store in activeTrips → notify nearby drivers

void acceptRide(Driver, Trip)                // trip.assignTrip(driver)
void rejectRide(Driver, Trip)
void startRide(Driver, Trip)                 // validates driver == trip.driver
void endRide(Driver, Trip)                   // validates driver, removes from activeTrips
void cancelRide(Rider, Trip)                 // validates rider, trip.cancelTrip(), remove from activeTrips

void rateDriver(Trip, String riderId, Integer rating)   // validates rider owns trip
void rateRider(Trip, String driverId, Integer rating)   // validates driver owns trip

void updateDriverLocation(String driverId, Location)
setDriverFindingStrategy / setFareCalculationStrategy   // injectable
```

---

## Key Concurrency Notes
| Scenario | Solution |
|---|---|
| Two drivers accepting same trip | `synchronized` on `Trip.assignTrip()` — only one succeeds, second hits `AssignedTripState.assignTrip` which throws |
| Trip state visibility across threads | `volatile TripState currentTripState` |
| Trip status visibility | `volatile TripStatus tripStatus` |
| Trip observer list | `CopyOnWriteArrayList` |
| User trip history | `CopyOnWriteArrayList` |
| Driver/Rider/Trip maps | `ConcurrentHashMap` |

---

## Flow Summary
- **Book:** Uber.bookRide → calculate fare → TripBuilder → store activeTrips → notify drivers via onUpdate
- **Accept:** Uber.acceptRide → Trip.assignTrip (synchronized) → RequestedTripState → set driver, ON_TRIP, → ASSIGNED, notify observers
- **Start:** Uber.startRide → Trip.startTrip → AssignedTripState → STARTED
- **End:** Uber.endRide → Trip.endTrip → StartedTripState → COMPLETED, driver ONLINE, add to history, remove from activeTrips
- **Cancel:** Uber.cancelRide → Trip.cancelTrip → CANCELLED, remove from activeTrips
