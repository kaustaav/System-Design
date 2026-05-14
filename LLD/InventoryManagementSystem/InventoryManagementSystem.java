package InventoryManagementSystem;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

enum ProductCategory {
    CLOTHING, WEARABLES, ELECTRONICS, FURNITURE
}

enum ProductMovementType {
    SELL, RESTOCK
}

class Product {
    private final String id;
    private final String name;
    private final ProductCategory productCategory;

    public Product(String id, String name, ProductCategory productCategory) {
        this.id = id;
        this.name = name;
        this.productCategory = productCategory;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ProductCategory getProductCategory() {
        return productCategory;
    }
}

class StockMovement {
    private final String productId;
    private final String warehouseId;
    private final Integer quantity;
    private final LocalDateTime timestamp;
    private final ProductMovementType productMovementType;

    public StockMovement(String productId, String warehouseId, Integer quantity,
            ProductMovementType productMovementType) {
        this.productId = productId;
        this.warehouseId = warehouseId;
        this.quantity = quantity;
        this.productMovementType = productMovementType;
        timestamp = LocalDateTime.now();
    }

    public String getProductId() {
        return productId;
    }

    public String getWarehouseId() {
        return warehouseId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public ProductMovementType getProductMovementType() {
        return productMovementType;
    }
}

interface RestockStrategy {
    // keeping product in signature as we might have another strategy for product
    // based percentage threhold etc
    public boolean shouldRestock(Product product, int quantity);

    public int getRestockQuantity(Product product);
}

interface InventoryObserver {
    public void notifyOnStockMovement(String warehouseId, String productId, int quantity);
}

class FixedRestockStrategy implements RestockStrategy {
    private final int fixedThreshold;
    private final int fixedCapacity;

    public FixedRestockStrategy(int fixedThreshold, int fixedCapacity) {
        this.fixedThreshold = fixedThreshold;
        this.fixedCapacity = fixedCapacity;
    }

    @Override
    public boolean shouldRestock(Product product, int quantity) {
        return quantity <= fixedThreshold;
    }

    @Override
    public int getRestockQuantity(Product product) {
        return fixedCapacity;
    }
}

class LowStockInventoryObserver implements InventoryObserver {
    private final int threshold;

    public LowStockInventoryObserver(int threshold) {
        this.threshold = threshold;
    }

    @Override
    public void notifyOnStockMovement(String warehouseId, String productId, int quantity) {
        if (quantity <= threshold)
            System.out.println(
                    "Product: " + productId + " under threshold in Warehouse: " + warehouseId + ". Needs restocking");
    }
}

class Warehouse {
    private final String id;
    private final String name;
    private final ConcurrentHashMap<String, Integer> inventory;
    private final CopyOnWriteArrayList<StockMovement> stockMovementRecords;
    private RestockStrategy restockStrategy;
    private final List<InventoryObserver> inventoryObservers;

    public Warehouse(String id, String name, RestockStrategy restockStrategy) {
        this.id = id;
        this.name = name;
        inventory = new ConcurrentHashMap<>();
        stockMovementRecords = new CopyOnWriteArrayList<>();
        this.restockStrategy = restockStrategy;
        inventoryObservers = new CopyOnWriteArrayList<InventoryObserver>();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public RestockStrategy getRestockStrategy() {
        return restockStrategy;
    }

    public void setRestockStrategy(RestockStrategy restockStrategy) {
        this.restockStrategy = restockStrategy;
    }

    public List<StockMovement> getStockMovements() {
        return Collections.unmodifiableList(stockMovementRecords);
    }

    public Integer getProductInventoryCount(String productId) {
        return inventory.getOrDefault(productId, 0);
    }

    protected void updateProductStock(String productId, Integer delta) {
        int newInventory = inventory.compute(productId, (key, quantity) -> {
            int current = quantity == null ? 0 : quantity;
            if (current + delta < 0)
                throw new IllegalStateException("Insufficient stock");
            return current + delta;
        });
        for (InventoryObserver inventoryObserver : inventoryObservers)
            inventoryObserver.notifyOnStockMovement(this.id, productId, newInventory);
    }

    public void addProductStock(String productId, Integer delta) {
        if (delta < 0)
            throw new IllegalStateException("Invalid operation");
        updateProductStock(productId, delta);
        stockMovementRecords.add(new StockMovement(productId, this.id, delta, ProductMovementType.RESTOCK));
    }

    public boolean shouldRestockProduct(Product product) {
        return restockStrategy.shouldRestock(product, inventory.getOrDefault(product, 0));
    }

    public void removeProductStock(String productId, Integer delta) {
        if (delta < 0)
            throw new IllegalStateException("Invalid operation");
        updateProductStock(productId, -delta);
        stockMovementRecords.add(new StockMovement(productId, this.id, delta, ProductMovementType.SELL));
    }

    public void restockProduct(Product product) {
        int restockDelta = restockStrategy.getRestockQuantity(product)
                - inventory.getOrDefault(product.getId(), 0);
        updateProductStock(product.getId(), restockDelta);
        stockMovementRecords
                .add(new StockMovement(product.getId(), this.id, restockDelta, ProductMovementType.RESTOCK));
    }

    public void addInventoryObserver(InventoryObserver inventoryObserver) {
        inventoryObservers.add(inventoryObserver);
    }
}

public class InventoryManagementSystem {
    private static volatile InventoryManagementSystem instance;
    private Map<String, Warehouse> warehouseMap;
    private Map<String, Product> productMap;

    protected InventoryManagementSystem() {
        warehouseMap = new ConcurrentHashMap<>();
        productMap = new ConcurrentHashMap<>();
    }

    public static InventoryManagementSystem getInstance() {
        if (instance == null)
            synchronized (InventoryManagementSystem.class) {
                if (instance == null)
                    instance = new InventoryManagementSystem();
            }
        return instance;
    }

    public void addProduct(Product product) {
        productMap.putIfAbsent(product.getId(), product);
    }

    public void removeProduct(Product product) {
        productMap.remove(product.getId());
    }

    public void addProducStockToWarehouse(String productId, String warehouseId, Integer delta) {
        Product product = productMap.getOrDefault(productId, null);
        if (product == null)
            throw new IllegalStateException("Product doesn't exist");
        Warehouse warehouse = warehouseMap.getOrDefault(warehouseId, null);
        if (warehouse == null)
            throw new IllegalStateException("Warehouse doesn't eixst");
        warehouse.addProductStock(productId, delta);
    }

    public void restockProductInWarehouse(String productId, String warehouseId) {
        Product product = productMap.getOrDefault(productId, null);
        if (product == null)
            throw new IllegalStateException("Product doesn't exist");
        Warehouse warehouse = warehouseMap.getOrDefault(warehouseId, null);
        if (warehouse == null)
            throw new IllegalStateException("Warehouse doesn't eixst");
        warehouse.restockProduct(product);
    }

    public void transferProductStockAcrossWarehouse(String productId, String srcWarehouseId, String destWarehouseId,
            int quantity) throws Exception {
        Product product = productMap.getOrDefault(productId, null);
        if (product == null)
            throw new IllegalStateException("Product doesn't exist");
        Warehouse srcWarehouse = warehouseMap.getOrDefault(srcWarehouseId, null);
        if (srcWarehouse == null)
            throw new IllegalStateException("Warehouse doesn't eixst");
        Warehouse destWarehouse = warehouseMap.getOrDefault(destWarehouseId, null);
        if (destWarehouse == null)
            throw new IllegalStateException("Warehouse doesn't eixst");
        try {
            srcWarehouse.removeProductStock(productId, quantity);
        } catch (Exception e) {
            throw new Exception(e);
        }

        try {
            destWarehouse.addProductStock(productId, quantity);
        } catch (Exception e) {
            srcWarehouse.addProductStock(productId, quantity);
            throw new Exception(e);
        }

    }

    public boolean shouldRestockProductInWarehouse(String productId, String warehouseId) {
        Product product = productMap.getOrDefault(productId, null);
        if (product == null)
            throw new IllegalStateException("Product doesn't exist");
        Warehouse warehouse = warehouseMap.getOrDefault(warehouseId, null);
        if (warehouse == null)
            throw new IllegalStateException("Warehouse doesn't eixst");
        return warehouse.shouldRestockProduct(product);
    }

    public void addObserverToWarehouse(InventoryObserver inventoryObserver, String warehouseId) {
        Warehouse warehouse = warehouseMap.getOrDefault(warehouseId, null);
        if (warehouse == null)
            throw new IllegalStateException("Warehouse doesn't eixst");
        warehouse.addInventoryObserver(inventoryObserver);
    }
}
