# BookMyShow — Entities

---

## Enums
```
SeatType:     REGULAR(100.0), PREMIUM(200.0)    // each carries a Double price
SeatState:    AVAILABLE, LOCKED, BOOKED
BookingState: INITIATED, PAYMENT_STARTED, CONFIRMED, CANCELLED, FAILED
PaymentMethods: CREDIT_CARD
PaymentState: PAYMENT_INITIATED, PAYMENT_FAILED, PAYMENT_SUCCEEDED
```

---

## Movie
```
String movieId, movieName
LocalDate releaseDate
String type                    // genre

getters
```

---

## Seat
```
String seatId, seatNo
SeatType seatType

Double getSeatPrice()          // delegates to seatType.getPrice()
getters
```

---

## Screen
```
String screenId
List<Seat> seats               // public final

void addSeat(Seat)
getters
```

---

## Theatre
```
String theatreId, theatreName
List<Screen> screens

void addScreen(Screen)
getters
```

---

## ShowSeat
```
final Seat seat
SeatState seatState            // starts AVAILABLE

Double getSeatPrice()          // delegates to seat.getSeatPrice()
setSeatState(SeatState)
getters
```

---

## Show
```
String showId
Movie movie
Screen screen
LocalDateTime startTime
Map<String, ShowSeat> showSeatMap    // HashMap, keyed by seatId, built from screen.seats at construction

getters
```

---

## Booking
```
String bookingId
Show show
BookingState bookingState      // starts INITIATED
List<ShowSeat> showSeats
Double amount

setBookingState(BookingState)
getters
```

---

## Payment
```
String paymentId
String bookingId
Double amount
PaymentState paymentState      // starts PAYMENT_INITIATED

setPaymentState(PaymentState)
getters
```

---

## interface PricingStrategyInterface              // Strategy pattern
```
Double getPricingAmount(Show show, List<ShowSeat> showSeats)
```
**Implementation:** WeekdaysPricingStrategy
- sums `showSeat.getSeatPrice()` across all selected seats

---

## interface PaymentStrategyInterface              // Strategy pattern
```
void makePayment(Payment payment)
```
**Implementation:** CreditCardPaymentStrategy
- sets `PAYMENT_SUCCEEDED` on success
- sets `PAYMENT_FAILED` + rethrows on exception

---

## BookingService
```
Map<String, ReentrantLock> showLocks        // ConcurrentHashMap — one lock per showId
PricingStrategyInterface pricingStrategy
Map<PaymentMethods, PaymentStrategyInterface> paymentMethodsMap

Booking createSeatBooking(Show show, List<ShowSeat> showSeats, PaymentMethods paymentMethods)
    // 1. computeIfAbsent ReentrantLock for showId → lock.lock()
    // 2. validate all seats AVAILABLE (throws if any are LOCKED/BOOKED)
    // 3. mark all seats LOCKED
    // 4. pricingStrategy.getPricingAmount → amount
    // 5. create Booking (INITIATED → PAYMENT_STARTED)
    // 6. create Payment → paymentStrategy.makePayment(payment)
    // 7. if PAYMENT_SUCCEEDED → BookingState.CONFIRMED, seats → BOOKED
    //    else                  → BookingState.FAILED, releaseSeats (→ AVAILABLE)
    // 8. lock.unlock() in finally

private void releaseSeats(List<ShowSeat>)   // reverts all seats to AVAILABLE
```

---

## BookMyShow                                       // Singleton pattern
```
Map<String, Movie> movieMap             // HashMap
Map<String, Theatre> theatreMap         // HashMap
Map<String, Map<String, Show>> showsMap // HashMap, movieId → theatreId → Show
BookingService bookingService           // initialized with WeekdaysPricingStrategy

static getInstance()                    // double-checked locking

void addMovie(Movie)
void removeMovie(String movieId)
void addTheatre(Theatre)
void addScreen(String theatreId, Screen)
void addShow(String showId, Movie, Theatre, Screen, LocalDateTime startTime)
void createBooking(Show, List<ShowSeat>, PaymentMethods)   // delegates to BookingService
```

---

## Key Concurrency Notes
| Scenario | Solution |
|---|---|
| Two users booking same seats in same show | `ReentrantLock` per `showId` — only one booking proceeds at a time per show |
| Lock granularity | Per-show locks — different shows can be booked concurrently |
| Atomic lock creation | `ConcurrentHashMap.computeIfAbsent` for showLocks |
| Lock leak on payment exception | `lock.unlock()` in `finally` block — always released |
| Seat state leak on crash | Limitation: `LOCKED` seats are never auto-released (no TTL in this implementation) |

---

## Flow Summary
- **Setup:** addMovie → addTheatre → addScreen → addShow (creates ShowSeat map from Screen.seats)
- **Book:** BookMyShow.createBooking → BookingService.createSeatBooking → acquire per-show lock → validate AVAILABLE → mark LOCKED → price → PAYMENT_STARTED → pay → CONFIRMED + BOOKED (or FAILED + AVAILABLE) → release lock