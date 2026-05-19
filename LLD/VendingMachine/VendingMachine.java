package VendingMachine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

enum Coin {
    ONE(1), TWO(2), FIVE(5), TEN(10);

    private final int value;

    Coin(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}

class Item {
    private final String id;
    private final String name;
    private final String description;
    private volatile double price;

    public Item(String id, String name, String description, double price) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.price = price;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }
}

interface MachineState {
    public void selectItem(VendingMachine instance, Item item);

    public void addMoney(VendingMachine instance, Coin coin);

    public void dispenseItem(VendingMachine instance);

    public void refundMoney(VendingMachine instance);
}

class IdleMachineState implements MachineState {

    @Override
    public void addMoney(VendingMachine instance, Coin coin) {
        throw new IllegalStateException("No product selected yet");
    }

    @Override
    public void dispenseItem(VendingMachine instance) {
        throw new IllegalStateException("No product selected yet");
    }

    @Override
    public void refundMoney(VendingMachine instance) {
        throw new IllegalStateException("No product selected yet");
    }

    @Override
    public void selectItem(VendingMachine instance, Item item) {
        instance.setSelectedItem(item);
        instance.setMachineState(new ItemSelectedMachineState());
    }
}

class ItemSelectedMachineState implements MachineState {

    @Override
    public void addMoney(VendingMachine instance, Coin coin) {
        instance.setBalance(instance.getBalance() + coin.getValue());
        instance.setMachineState(new HasMoneyMachineState());
    }

    @Override
    public void dispenseItem(VendingMachine instance) {
        throw new IllegalStateException("Please add coin to dispense item");

    }

    @Override
    public void refundMoney(VendingMachine instance) {
        throw new IllegalStateException("No balance to refund");

    }

    @Override
    public void selectItem(VendingMachine instance, Item item) {
        instance.setSelectedItem(item);
    }
}

class HasMoneyMachineState implements MachineState {

    @Override
    public void addMoney(VendingMachine instance, Coin coin) {
        instance.setBalance(instance.getBalance() + coin.getValue());
    }

    @Override
    public void dispenseItem(VendingMachine instance) {
        Item selectedItem = instance.getSelectedItem();
        if (instance.getBalance() >= selectedItem.getPrice()) {
            instance.reduceSelectedProductStock();
            instance.setBalance(instance.getBalance() - selectedItem.getPrice());
            refundMoney(instance);
        } else {
            throw new IllegalStateException("Not enough balance");
        }

    }

    @Override
    public void refundMoney(VendingMachine instance) {
        if (instance.getBalance() > 0.0D) {
            System.out.println("Initiating refund");
            instance.setBalance(0.0D);
            System.out.println("Your money has been successfully refunded");
        }
        instance.resetInstance();
    }

    @Override
    public void selectItem(VendingMachine instance, Item item) {
        throw new IllegalStateException("Product already selected");
    }
}

public class VendingMachine {
    private static volatile VendingMachine instance;
    private final Map<String, Item> itemMap;
    private final Map<String, Integer> inventory;
    private MachineState machineState;
    private Item selectedItem;
    private Double balance;

    private VendingMachine() {
        itemMap = new ConcurrentHashMap<>();
        inventory = new ConcurrentHashMap<>();
        machineState = new IdleMachineState();
        balance = 0.0D;
    }

    public static VendingMachine getInstance() {
        if (instance == null)
            synchronized (VendingMachine.class) {
                if (instance == null)
                    instance = new VendingMachine();
            }
        return instance;
    }

    public void updateItemPrice(Item item, double price) {
        synchronized (this) {
            if (!itemMap.containsKey(item.getId()))
                throw new IllegalArgumentException("Item not found");
            item.setPrice(price);
        }
    }

    public void addItem(Item item, int quantity) {
        synchronized (this) {
            if (itemMap.containsKey(item.getId())) {
                inventory.compute(item.getId(), (k, v) -> v + quantity);
            } else {
                itemMap.put(item.getId(), item);
                inventory.put(item.getId(), quantity);
            }
        }
    }

    public void selectItem(Item item) {
        if (!itemMap.containsKey(item.getId()))
            throw new IllegalArgumentException("Item not found");
        synchronized (this) {
            if (isProductAvailable(item))
                machineState.selectItem(this, item);
        }

    }

    protected void reduceSelectedProductStock() {
        inventory.compute(selectedItem.getId(), (k, v) -> v - 1);
    }

    public void addMoney(Coin coin) {
        synchronized (this) {
            machineState.addMoney(this, coin);
        }

    }

    public void refundMoney() {
        synchronized (this) {
            machineState.refundMoney(this);
        }

    }

    public void dispenseItem() {
        synchronized (this) {
            machineState.dispenseItem(this);
        }
    }

    protected void resetInstance() {
        machineState = new IdleMachineState();
        selectedItem = null;
    }

    protected void setMachineState(MachineState machineState) {
        this.machineState = machineState;
    }

    public Item getSelectedItem() {
        return selectedItem;
    }

    protected void setSelectedItem(Item selectedItem) {
        this.selectedItem = selectedItem;
    }

    public Double getBalance() {
        return balance;
    }

    protected void setBalance(Double balance) {
        this.balance = balance;
    }

    protected boolean isProductAvailable(Item item) {
        if (!itemMap.containsKey(item.getId()))
            throw new IllegalArgumentException("Item not found");
        return inventory.getOrDefault(item.getId(), 0) > 0;
    }

}
