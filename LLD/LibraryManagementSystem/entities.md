# Library Management System — Entities

---

## Enums / States
```
BookStates: AVAILABLE, RESERVED, CHECKED_OUT  (not stored — enforced via State pattern)
```

---

## User
```
String id, name, contact
getters
```

---

## interface PaymentStrategy                          // Strategy pattern
```
void payFees(Double fees)
```
**Implementations:** CreditCardPaymentStrategy, UpiPaymentStrategy

---

## interface Observer                                 // Observer pattern
```
void notifyOnReturn(Book book)
```
**Implementation:** AvailabilityObserver
- polls first user from Book.reservations queue
- finds copy where currentUser == null
- calls copy.reserveBook(user) → sets RESERVED state + assigns user
- CopyOnWriteArrayList for thread-safe iteration

---

## interface BookState                                // State pattern
```
void reserveBook(BookCopy bookCopy, User user)
void checkoutBook(BookCopy bookCopy, User user)
void returnBook(BookCopy bookCopy, User user, PaymentStrategy paymentStrategy)
```

### AvailableBookState
| Method | Behaviour |
|---|---|
| reserveBook | sets currentUser + transitions to ReservedBookState |
| checkoutBook | creates Lease, sets currentUser, transitions to CheckedOutBookState |
| returnBook | throws |

### ReservedBookState
| Method | Behaviour |
|---|---|
| reserveBook | adds user to Book.reservations queue |
| checkoutBook | validates currentUser == caller, creates Lease, transitions to CheckedOutBookState |
| returnBook | throws |

### CheckedOutBookState
| Method | Behaviour |
|---|---|
| reserveBook | adds user to Book.reservations queue |
| checkoutBook | throws |
| returnBook | validates user, computes fees, processes payment, releases lease, sets AVAILABLE, clears currentUser, notifyObservers() |

---

## interface FeesComputeStrategy                      // Strategy pattern
```
double computeFees(Lease lease)
```
**Implementation:** FixedFeesStrategy(double rate)
- returns 0 if within dueDate
- returns (today - dueDate) * rate if overdue

---

## Book
```
String id, name
List<BookCopy> bookCopies                             // ArrayList, init on construction
Queue<User> reservations                              // ConcurrentLinkedQueue — FIFO reservation queue
List<Observer> observers                              // CopyOnWriteArrayList — thread-safe

void reserveBook(User user)                          // adds to reservations queue
void addReservation(User user)
void addObserver(Observer observer)
void notifyObservers()                               // called on copy return
getters
```

---

## BookCopy
```
String id
Book book
volatile BookState bookState                         // volatile — visible across threads
User currentUser                                     // null if available

void reserveBook(User user)    → delegates to bookState.reserveBook(this, user)
void checkoutBook(User user)   → delegates to bookState.checkoutBook(this, user)
void returnBook(User user, PaymentStrategy) → delegates to bookState.returnBook(this, user, ps)
getters + setters for bookState, currentUser
```

---

## Lease
```
String id                    // UUID
BookCopy bookCopy
User user
LocalDate leaseDate          // set on creation
LocalDate dueDate            // leaseDate + 14 days
getters
```

---

## LeaseManagementSystem                              // Singleton pattern
```
Map<String, Lease> leaseMap                          // ConcurrentHashMap, keyed by copyId
volatile FeesComputeStrategy feesComputeStrategy     // volatile — set post-construction via setter

static getInstance()                                 // double-checked locking
void createLease(Lease lease)
void releaseLease(BookCopy bookCopy)
Lease getLeaseForBookCopy(BookCopy bookCopy)
List<Lease> getAllLeaseForUser(String userId)         // stream filter on leaseMap
double computeFees(Lease lease)                      // delegates to feesComputeStrategy
void setFeesComputeStrategy(FeesComputeStrategy)     // inject after getInstance()
void payFees(PaymentStrategy paymentStrategy)
```

---

## LibraryManagementSystem                           // Singleton pattern
```
LeaseManagementSystem leaseManagementSystem
Map<String, Book> bookMap                            // ConcurrentHashMap
Map<String, User> userMap                            // ConcurrentHashMap

static getInstance()                                 // double-checked locking
void addBook(Book book)
void addUser(User user)
List<BookCopy> getBookById(String id)

void checkoutBookByUser(User user, BookCopy bookCopy)
    // synchronized(user) — per-user lock to enforce 3-book limit atomically
    // assumes canonical User instances from userMap

void returnBookByUser(User user, BookCopy bookCopy, PaymentStrategy paymentStrategy)
void reserveBookByUser(User user, BookCopy bookCopy)
List<Lease> getAllListByUser(User user)
```

---

## Key Concurrency Notes
| Scenario | Solution |
|---|---|
| BookCopy state visibility | `volatile BookState bookState` |
| Reservation queue thread safety | `ConcurrentLinkedQueue` |
| Observer list thread safety | `CopyOnWriteArrayList` |
| Lease map thread safety | `ConcurrentHashMap` |
| 3-book checkout limit | `synchronized(user)` — locks per user object |
| FeesStrategy set post-construction | `volatile FeesComputeStrategy` |

---

## Flow Summary
- **Checkout:** LMS → BookCopy.checkoutBook → AvailableBookState → create Lease → CHECKED_OUT
- **Reserve:** LMS → BookCopy.reserveBook → CheckedOutBookState → add to queue
- **Return:** LMS → BookCopy.returnBook → CheckedOutBookState → pay fees → release lease → AVAILABLE → notifyObservers
- **Auto-assign on return:** AvailabilityObserver → poll queue → find available copy → RESERVED
- **Reserved checkout:** LMS → BookCopy.checkoutBook → ReservedBookState → validate user → create Lease → CHECKED_OUT
