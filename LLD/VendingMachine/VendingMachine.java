package VendingMachine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

enum Coin {
    ONE(1),
    TWO(2),
    FIVE(5),
    TEN(10),
    TWENTY(20),
    FIFTY(50),
    HUNDRED(100);

    private int value;

    Coin(int value) {
        this.value = value;
    }

    public int getValue() {
        return this.value;
    }

}

class Product {
    private final String id;
    private final String name;
    private volatile double price;

    public Product(String id, String name, double price) {
        this.id = id;
        this.name = name;
        this.price = price;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }
}

interface MachineState {
    public void selectProduct(VendingMachine instance, Product product);

    public void addMoney(VendingMachine instance, Coin coin);

    public void refund(VendingMachine instance);

    public void dispense(VendingMachine instance);
}

class IdleMachineState implements MachineState {
    @Override
    public void selectProduct(VendingMachine instance, Product product) {
        instance.setSelectedProduct(product);
        instance.setCurrentState(new ItemSelectedMachineState());
    }

    @Override
    public void addMoney(VendingMachine instance, Coin coin) {
        System.out.println("No product selected");
    }

    @Override
    public void refund(VendingMachine instance) {
        System.out.println("No Product Selected");
    }

    @Override
    public void dispense(VendingMachine instance) {
        System.out.println("No Product Selected");
    }
}

class ItemSelectedMachineState implements MachineState {
    @Override
    public void selectProduct(VendingMachine instance, Product product) {
        instance.setSelectedProduct(product);
    }

    @Override
    public void addMoney(VendingMachine instance, Coin coin) {
        instance.setBalance(instance.getBalance() + coin.getValue());
        instance.setCurrentState(new HasMoneyMachineState());
    }

    @Override
    public void refund(VendingMachine instance) {
        System.out.println("No balance added yet");
    }

    @Override
    public void dispense(VendingMachine instance) {
        System.out.println("No balance added yet");
    }
}

class HasMoneyMachineState implements MachineState {
    @Override
    public void selectProduct(VendingMachine instance, Product product) {
        System.out.println("Product already selected");
    }

    @Override
    public void addMoney(VendingMachine instance, Coin coin) {
        instance.setBalance(instance.getBalance() + coin.getValue());
    }

    @Override
    public void refund(VendingMachine instance) {
        System.out.println("Refund Initiated");
        instance.setBalance(0.0);
        instance.resetInstance();
    }

    @Override
    public void dispense(VendingMachine instance) {
        if (instance.getSelectedProduct().getPrice() <= instance.getBalance()) {
            if (!instance.isProductAvailable(instance.getSelectedProduct()))
                throw new IllegalStateException("Product out of stock");
            instance.reduceSelectedProductStock();
            instance.setBalance(instance.getBalance() - instance.getSelectedProduct().getPrice());
            this.refund(instance);
        } else
            System.out.println("Insufficient Money");
    }
}

public class VendingMachine {
    private static volatile VendingMachine instance;
    private final Map<String, Product> productMap;
    private final Map<String, Integer> inventory;
    private Product selectedProduct;
    private MachineState currentState;
    private Double balance;

    private VendingMachine() {
        productMap = new ConcurrentHashMap<>();
        inventory = new ConcurrentHashMap<>();
        selectedProduct = null;
        currentState = new IdleMachineState();
        balance = 0.0;
    }

    public static VendingMachine getInstance() {
        if (instance == null)
            synchronized (VendingMachine.class) {
                if (instance == null)
                    instance = new VendingMachine();
            }
        return instance;
    }

    public void addProduct(Product product, int quantity) {
        synchronized (this) {
            if (productMap.containsKey(product.getId()))
                inventory.compute(product.getId(), (k, v) -> v + quantity);
            else {
                productMap.put(product.getId(), product);
                inventory.put(product.getId(), quantity);
            }
        }
    }

    public void removeProduct(Product product) {
        synchronized (this) {

        }
    }

    public void updatePrice(Product product, double price) {
        synchronized (this) {
            if (!productMap.containsKey(product.getId()))
                throw new IllegalStateException("Product not found");
            product.setPrice(price);
        }
    }

    protected void reduceSelectedProductStock() {
        inventory.put(selectedProduct.getId(), inventory.get(selectedProduct.getId()) - 1);
    }

    public void selectProduct(Product product) {
        synchronized (this) {
            product = productMap.getOrDefault(product.getId(), null);
            if (product == null)
                throw new IllegalStateException("Product doesn't exist");
            currentState.selectProduct(this, product);
        }
    }

    public void addMoney(Coin coin) {
        synchronized (this) {
            currentState.addMoney(this, coin);
        }
    }

    public void refundMoney() {
        synchronized (this) {
            currentState.refund(this);
        }
    }

    public void dispenseProduct() {
        synchronized (this) {
            currentState.dispense(this);
        }
    }

    protected void resetInstance() {
        selectedProduct = null;
        currentState = new IdleMachineState();
    }

    protected void setCurrentState(MachineState currentState) {
        this.currentState = currentState;
    }

    protected void setBalance(Double balance) {
        this.balance = balance;
    }

    public Product getSelectedProduct() {
        return selectedProduct;
    }

    public Double getBalance() {
        return balance;
    }

    protected void setSelectedProduct(Product selectedProduct) {
        this.selectedProduct = selectedProduct;
    }

    public boolean isProductAvailable(Product product) {
        product = productMap.getOrDefault(product.getId(), null);
        if (product == null)
            throw new IllegalStateException("Product doesn't exist");
        return inventory.getOrDefault(product.getId(), 0) > 0;
    }
}
