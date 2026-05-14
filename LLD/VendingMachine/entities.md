# Vending Machine — Entities

---

## Enums
```
Coin: ONE(1), TWO(2), FIVE(5), TEN(10), TWENTY(20), FIFTY(50), HUNDRED(100)
      each has int value via getValue()
```

---

## Product
```
String id, name
volatile double price        // volatile — admin can update price concurrently

getters
void setPrice(double price)
```

---

## interface MachineState                            // State pattern
```
void selectProduct(VendingMachine instance, Product product)
void addMoney(VendingMachine instance, Coin coin)
void refund(VendingMachine instance)
void dispense(VendingMachine instance)
```
Note: VendingMachine passed as parameter — states are decoupled from singleton

### State Transition Table
| State | selectProduct | addMoney | refund | dispense |
|---|---|---|---|---|
| IdleMachineState | ✓ set product → ItemSelected | prints "no product" | prints "no product" | prints "no product" |
| ItemSelectedMachineState | ✓ change product | ✓ add to balance → HasMoney | prints "no balance" | prints "no balance" |
| HasMoneyMachineState | prints "already selected" | ✓ add to balance | ✓ zero balance + reset → Idle | ✓ check stock + balance, dispense, return change → Idle |

### Dispense flow (HasMoneyMachineState)
1. Check `balance >= product.price` — else print "Insufficient Money"
2. Check `isProductAvailable()` — else throw "Product out of stock"
3. `reduceSelectedProductStock()`
4. `setBalance(balance - price)`
5. Call `refund()` — returns remaining balance as change, resets to Idle

---

## VendingMachine                                   // Singleton pattern
```
Map<String, Product> productMap      // ConcurrentHashMap
Map<String, Integer> inventory       // ConcurrentHashMap, keyed by productId
Product selectedProduct              // null when idle
MachineState currentState            // starts as IdleMachineState
double balance                       // current inserted amount

static getInstance()                 // double-checked locking

// User operations — all synchronized(this)
void selectProduct(Product product)
void addMoney(Coin coin)
void refundMoney()
void dispenseProduct()

// Admin operations — all synchronized(this)
void addProduct(Product product, int quantity)   // restocks if exists, adds new if not
void removeProduct(Product product)
void updatePrice(Product product, double price)

// Internal helpers — protected
void reduceSelectedProductStock()
boolean isProductAvailable(Product product)
void resetInstance()                 // clears selectedProduct + resets state to Idle
void setCurrentState(MachineState)
void setBalance(double)
void setSelectedProduct(Product)
```

---

## Key Concurrency Notes
| Scenario | Solution |
|---|---|
| All state transitions | `synchronized(this)` on all public VendingMachine methods |
| Admin price update | `volatile double price` on Product |
| Product/inventory maps | `ConcurrentHashMap` |
| Restock via compute | `inventory.compute(id, (k,v) -> v + quantity)` — atomic increment |

---

## Flow Summary
- **Select:** selectProduct → IdleMachineState → set selectedProduct → ItemSelectedState
- **Insert money:** addMoney → ItemSelectedState → add to balance → HasMoneyState
- **More money:** addMoney → HasMoneyState → accumulate balance
- **Dispense:** dispenseProduct → HasMoneyState → check balance + stock → dispense → return change → Idle
- **Refund:** refundMoney → HasMoneyState → zero balance → reset to Idle
- **Admin restock:** addProduct → synchronized → compute inventory increment
