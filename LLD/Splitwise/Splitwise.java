package Splitwise;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.management.RuntimeErrorException;

enum SplitType {
    EXACT,
    EQUAL,
    PERCENTAGE
}

abstract class Split {
    protected final String userId;
    protected Double amount;

    public Split(String userId) {
        this.userId = userId;
        this.amount = 0.0;
    }

    public String getUserId() {
        return userId;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }
}

class EqualSplit extends Split {
    public EqualSplit(String userId) {
        super(userId);
    }
}

class ExactSplit extends Split {
    public ExactSplit(String userId, Double amount) {
        super(userId);
        setAmount(amount);
    }
}

class PercentageSplit extends Split {
    private final Float percentage;

    public PercentageSplit(String userId, Float percentage) {
        super(userId);
        this.percentage = percentage;
    }

    public Float getPercentage() {
        return percentage;
    }
}

interface SplitStrategyInterface {
    public void validateSplit(List<Split> splits, Double totalAmount);

    public void calculateSplit(List<Split> splits, Double totalAmount);
}

class EqualSplitStrategy implements SplitStrategyInterface {
    @Override
    public void validateSplit(List<Split> splits, Double totalAmount) {
        return;
    }

    @Override
    public void calculateSplit(List<Split> splits, Double totalAmount) {
        Double totalShareSoFar = 0.0;
        Double equalShare = totalAmount / splits.size();
        for (int i = 0; i < splits.size() - 1; i++) {
            splits.get(i).setAmount(equalShare);
            totalShareSoFar += equalShare;
        }

        splits.get(splits.size() - 1).setAmount(totalAmount - totalShareSoFar);
    }
}

class ExactSplitStrategy implements SplitStrategyInterface {
    private static final Double THRESHOLD = 0.01;

    @Override
    public void validateSplit(List<Split> splits, Double totalAmount) {
        Double totalShareSoFar = 0.0;

        for (int i = 0; i < splits.size(); i++) {
            totalShareSoFar += splits.get(i).getAmount();
        }

        if (Math.abs(totalAmount - totalShareSoFar) > THRESHOLD)
            throw new IllegalStateException("Individual Amounts not summing up to Total Amount");
    }

    @Override
    public void calculateSplit(List<Split> splits, Double totalAmount) {
        return;
    }
}

class PercentageSplitStrategy implements SplitStrategyInterface {
    private static final Double THRESHOLD = 0.01;

    @Override
    public void validateSplit(List<Split> splits, Double totalAmount) {
        Double totalPercentageSoFar = 0.0;

        for (int i = 0; i < splits.size(); i++) {
            Split split = splits.get(i);
            totalPercentageSoFar += ((PercentageSplit) split).getPercentage();
        }

        if (Math.abs(100.00 - totalPercentageSoFar) > THRESHOLD)
            throw new IllegalStateException("Individual Percentages not summing up to Total Amount");
    }

    @Override
    public void calculateSplit(List<Split> splits, Double totalAmount) {
        Double totalAmountSoFar = 0.0;
        for (int i = 0; i < splits.size() - 1; i++) {
            Split split = splits.get(i);
            Double amountAllocated = totalAmount * ((PercentageSplit) split).getPercentage() / 100.00;
            totalAmountSoFar += amountAllocated;
            split.setAmount(amountAllocated);
        }

        splits.get(splits.size() - 1).setAmount(totalAmount - totalAmountSoFar);
    }
}

class User {
    private final String id;
    private final String name;
    private final String contact;
    private final String email;

    public User(String id, String name, String contact, String email) {
        this.id = id;
        this.name = name;
        this.contact = contact;
        this.email = email;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getContact() {
        return contact;
    }

    public String getEmail() {
        return email;
    }
}

class Expenses {
    private final String id;
    private final Double amount;
    private final String paidByUserId;
    private final LocalDateTime timestamp;
    private final String description;
    private final String groupId;
    private final SplitType splitType;
    private final List<Split> splits;

    public Expenses(String id, Double amount, String paidByUserId, LocalDateTime timestamp,
            String description, String groupId, SplitType splitType, List<Split> splits) {
        this.id = id;
        this.amount = amount;
        this.paidByUserId = paidByUserId;
        this.timestamp = timestamp;
        this.description = description;
        this.groupId = groupId;
        this.splitType = splitType;
        this.splits = Collections.unmodifiableList(new ArrayList<>(splits));
    }

    public String getId() {
        return id;
    }

    public Double getAmount() {
        return amount;
    }

    public String getPaidByUserId() {
        return paidByUserId;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public String getDescription() {
        return description;
    }

    public String getGroupId() {
        return groupId;
    }

    public SplitType getSplitType() {
        return splitType;
    }

    public List<Split> getSplits() {
        return splits;
    }
}

class BalanceSheet {
    private ConcurrentHashMap<String, ConcurrentHashMap<String, Double>> balances;

    public BalanceSheet() {
        this.balances = new ConcurrentHashMap<>();
    }

    public void updateBalances(String fromUser, String toUser, Double amount) {
        balances.computeIfAbsent(fromUser, k -> new ConcurrentHashMap<>())
                .merge(toUser, amount, Double::sum);
        balances.computeIfAbsent(toUser, k -> new ConcurrentHashMap<>())
                .merge(fromUser, -amount, Double::sum);
    }

    public void settleBalances(String fromUser, String toUser, Double amount) {
        updateBalances(fromUser, toUser, -amount);
    }

    public ConcurrentHashMap<String, Double> getBalancesForUser(String userId) {
        return balances.getOrDefault(userId, new ConcurrentHashMap<>());
    }

    public Double getBalance(String user1, String user2) {
        return balances.getOrDefault(user1, new ConcurrentHashMap<>()).getOrDefault(user2, 0.0);
    }
}

class Group {
    private final String id;
    private final String groupName;
    private final List<String> userIds;
    private final List<String> expenseIds;

    public Group(String id, String groupName) {
        this.id = id;
        this.groupName = groupName;
        this.userIds = new CopyOnWriteArrayList<>();
        this.expenseIds = new CopyOnWriteArrayList<>();
    }

    public String getId() {
        return id;
    }

    public String getGroupName() {
        return groupName;
    }

    public List<String> getUsers() {
        return userIds;
    }

    public List<String> getExpenses() {
        return expenseIds;
    }

    public void addUser(String userId) {
        if (!userIds.contains(userId))
            userIds.add(userId);
        else
            throw new RuntimeErrorException(null);
    }

    public void addExpense(String expenseId) {
        if (!expenseIds.contains(expenseId))
            expenseIds.add(expenseId);
        else
            throw new RuntimeErrorException(null);
    }
}

public class Splitwise {
    private static volatile Splitwise instance;
    private final BalanceSheet balanceSheet;
    private final ConcurrentHashMap<String, User> userMap;
    private final ConcurrentHashMap<String, Group> groupMap;
    private final ConcurrentHashMap<String, Expenses> expensesMap;
    private final Map<SplitType, SplitStrategyInterface> strategies;

    public Splitwise() {
        this.balanceSheet = new BalanceSheet();
        this.userMap = new ConcurrentHashMap<>();
        this.expensesMap = new ConcurrentHashMap<>();
        this.strategies = new HashMap<>();
        this.groupMap = new ConcurrentHashMap<>();

        strategies.put(SplitType.EQUAL, new EqualSplitStrategy());
        strategies.put(SplitType.EXACT, new ExactSplitStrategy());
        strategies.put(SplitType.PERCENTAGE, new PercentageSplitStrategy());
    }

    public static Splitwise getInstance() {
        if (instance == null) {
            synchronized (Splitwise.class) {
                if (instance == null) {
                    instance = new Splitwise();
                }
            }
        }
        return instance;
    }

    public void addExpense(String id, String userId, Double amount, String description, List<Split> splits,
            SplitType splitType, String groupId) {
        if (!userMap.containsKey(userId))
            throw new RuntimeErrorException(null);
        if (groupId != null && !groupMap.containsKey(groupId))
            throw new RuntimeErrorException(null);
        SplitStrategyInterface strategy = strategies.get(splitType);
        strategy.validateSplit(splits, amount);
        strategy.calculateSplit(splits, amount);

        Expenses expenses = new Expenses(id, amount, userId, LocalDateTime.now(), description, groupId, splitType,
                splits);
        expensesMap.put(id, expenses);
        if (groupId != null)
            groupMap.get(groupId).addExpense(expenses.getId());

        for (Split split : splits) {
            balanceSheet.updateBalances(userId, split.getUserId(), split.getAmount());
        }
    }

    public void settleBalance(String fromUserId, String toUserId, Double settleAmount) {
        if (!userMap.containsKey(fromUserId) || !userMap.containsKey(toUserId))
            throw new RuntimeErrorException(null);
        balanceSheet.settleBalances(fromUserId, toUserId, settleAmount);
    }

    public void addUser(User user) {
        if (!userMap.containsKey(user.getId()))
            userMap.put(user.getId(), user);
        else
            throw new RuntimeErrorException(null);
    }

    public void addGroup(Group group) {
        if (!groupMap.containsKey(group.getId()))
            groupMap.put(group.getId(), group);
        else
            throw new RuntimeErrorException(null);
    }

    public void addMemberToGroup(String groupId, String userId) {
        if (!userMap.containsKey(userId))
            throw new RuntimeErrorException(null);
        if (!groupMap.containsKey(groupId))
            throw new RuntimeErrorException(null);
        groupMap.get(groupId).addUser(userId);
    }

    public void simplifyDebts(String groupId) {
        if (!groupMap.containsKey(groupId))
            throw new IllegalStateException("Group not found");

        List<String> userIds = groupMap.get(groupId).getUsers();

        // Step 1: collapse bilateral balances into a single net per user
        // positive = net creditor (owed money), negative = net debtor (owes money)
        Map<String, Double> net = new HashMap<>();
        for (String uid : userIds) {
            Map<String, Double> row = balanceSheet.getBalancesForUser(uid);
            double sum = 0.0;
            for (String other : userIds)
                if (!other.equals(uid))
                    sum += row.getOrDefault(other, 0.0);
            net.put(uid, sum);
        }

        // Step 2: max-heap of creditors (largest balance first),
        //         max-heap of debtors (most negative first)
        PriorityQueue<String> creditors = new PriorityQueue<>(
                (a, b) -> Double.compare(net.get(b), net.get(a)));
        PriorityQueue<String> debtors = new PriorityQueue<>(
                (a, b) -> Double.compare(net.get(a), net.get(b)));

        for (String uid : userIds) {
            if (net.get(uid) > 0.01)       creditors.offer(uid);
            else if (net.get(uid) < -0.01) debtors.offer(uid);
        }

        // Step 3: greedily settle largest debtor against largest creditor
        while (!creditors.isEmpty() && !debtors.isEmpty()) {
            String creditor = creditors.poll();
            String debtor   = debtors.poll();
            double amount   = Math.min(net.get(creditor), -net.get(debtor));

            System.out.println(userMap.get(debtor).getName() + " pays "
                    + userMap.get(creditor).getName() + " RS " + amount);

            net.merge(creditor, -amount, Double::sum);
            net.merge(debtor,    amount, Double::sum);

            if (net.get(creditor) > 0.01) creditors.offer(creditor);
            if (net.get(debtor)  < -0.01)  debtors.offer(debtor);
        }
    }

    public void showBalancesForUser(String userId) {
        if (!userMap.containsKey(userId))
            throw new RuntimeErrorException(null);
        Map<String, Double> userBalances = balanceSheet.getBalancesForUser(userId);
        for (Map.Entry<String, Double> balance : userBalances.entrySet()) {
            if (balance.getValue() < 0)
                System.out.println(userMap.get(userId).getName() + " owes " + userMap.get(balance.getKey()).getName()
                        + " RS " + -1 * balance.getValue());
            else
                System.out.println(userMap.get(balance.getKey()).getName() + " owes " + userMap.get(userId).getName()
                        + " RS " + balance.getValue());
        }
    }
}