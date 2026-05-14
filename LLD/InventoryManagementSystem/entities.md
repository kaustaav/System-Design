# Inventory Management System — Entities

---

## Enums
```
ProductCategory:     CLOTHING, WEARABLES, ELECTRONICS, FURNITURE
ProductMovementType: SELL, RESTOCK
```

---

## Product
```
String id, name
ProductCategory productCategory

getters
```

---

## StockMovement
```
String productId, warehouseId
Integer quantity
LocalDateTime timestamp           // set at construction
ProductMovementType productMovementType

getters
```

---

## interface RestockStrategy                             // Strategy pattern
```
boolean shouldRestock(Product product, int quantity)
int getRestockQuantity(Product product)
```
**Implementation:** FixedRestockStrategy(int fixedThreshold, int fixedCapacity)
- shouldRestock: returns `quantity <= fixedThreshold`
- getRestockQuantity: returns `fixedCapacity`

---

## interface InventoryObserver                          // Observer pattern
```
void notifyOnStockMovement(String warehouseId, String productId, int quantity)
```
**Implementation:** LowStockInventoryObserver(int threshold)
- prints low-stock warning when `quantity <= threshold`

---

## Warehouse
```
String id, name
ConcurrentHashMap<String, Integer> inventory          // productId → stock count
CopyOnWriteArrayList<StockMovement> stockMovementRecords
RestockStrategy restockStrategy                       // injectable via setter
List<InventoryObserver> inventoryObservers            // CopyOnWriteArrayList

Integer getProductInventoryCount(String productId)
List<StockMovement> getStockMovements()               // unmodifiable view

// Core stock mutation — all paths go through here
protected void updateProductStock(String productId, Integer delta)
    // inventory.compute() — atomic; throws IllegalStateException if result < 0
    // notifies all observers with new count after update

void addProductStock(String productId, Integer delta)   // delta must be >= 0 → updateProductStock(+delta) → record RESTOCK
void removeProductStock(String productId, Integer delta) // delta must be >= 0 → updateProductStock(-delta) → record SELL
void restockProduct(Product product)                    // fills to fixedCapacity → updateProductStock(delta) → record RESTOCK

boolean shouldRestockProduct(Product product)           // delegates to restockStrategy
void addInventoryObserver(InventoryObserver observer)
void setRestockStrategy(RestockStrategy)
```

---

## InventoryManagementSystem                            // Singleton pattern
```
Map<String, Warehouse> warehouseMap       // ConcurrentHashMap
Map<String, Product> productMap           // ConcurrentHashMap

static getInstance()                      // double-checked locking

void addProduct(Product)                  // putIfAbsent
void removeProduct(Product)

void addProductStockToWarehouse(String productId, String warehouseId, Integer delta)
void restockProductInWarehouse(String productId, String warehouseId)

void transferProductStockAcrossWarehouse(String productId, String srcWarehouseId, String destWarehouseId, int quantity)
    // removeProductStock from src → addProductStock to dest
    // compensating rollback: if dest add fails, re-add to src

boolean shouldRestockProductInWarehouse(String productId, String warehouseId)

void addObserverToWarehouse(InventoryObserver, String warehouseId)
```

---

## Key Concurrency Notes
| Scenario | Solution |
|---|---|
| Concurrent stock updates on same product | `inventory.compute()` in `updateProductStock` — atomic CAS-based increment/decrement |
| Negative stock prevention | `compute` throws `IllegalStateException` if result < 0 — atomic check-and-update |
| Stock movement audit log | `CopyOnWriteArrayList` — safe for concurrent appends + iteration |
| Observer notification list | `CopyOnWriteArrayList` — iteration safe during concurrent add |
| All maps | `ConcurrentHashMap` |
| Cross-warehouse transfer | Compensating transaction — not globally atomic, but each warehouse update is atomic |

---

## Flow Summary
- **Add stock:** IMS.addProductStockToWarehouse → Warehouse.addProductStock → updateProductStock (atomic compute) → notify observers → record RESTOCK
- **Remove stock:** Warehouse.removeProductStock (no IMS-level method) → updateProductStock(-delta, throws if negative) → notify observers → record SELL
- **Auto-restock:** IMS.restockProductInWarehouse → Warehouse.restockProduct → fill to capacity → updateProductStock → notify observers
- **Transfer:** IMS.transferProductStockAcrossWarehouse → remove from src → add to dest → rollback src on failure
- **Low-stock alert:** LowStockInventoryObserver.notifyOnStockMovement → prints warning whenever post-update stock <= threshold