# Splitwise — Entities

---

## Enums
```
SplitType: EXACT, EQUAL, PERCENTAGE
```

---

## abstract Split
```
protected final String userId
protected Double amount        // starts 0.0; set by strategy or at construction

getUserId()
getAmount()
setAmount(Double)
```

### EqualSplit(String userId)
- amount set to 0.0, filled in by EqualSplitStrategy.calculateSplit

### ExactSplit(String userId, Double amount)
- amount set at construction (caller provides it explicitly)

### PercentageSplit(String userId, Float percentage)
- Float percentage (extra field)
- amount computed by PercentageSplitStrategy.calculateSplit
- getPercentage()

---

## interface SplitStrategyInterface                  // Strategy pattern
```
void validateSplit(List<Split> splits, Double totalAmount)
void calculateSplit(List<Split> splits, Double totalAmount)
```

### EqualSplitStrategy
- validateSplit: no-op
- calculateSplit: equalShare = total / n; assigns to first n-1 splits; remainder to last (avoids float rounding)

### ExactSplitStrategy
- THRESHOLD = 0.01
- validateSplit: sums all split.amount → throws if |total - sum| > THRESHOLD
- calculateSplit: no-op (amounts already set on ExactSplit at construction)

### PercentageSplitStrategy
- THRESHOLD = 0.01
- validateSplit: sums percentages → throws if |100 - sum| > THRESHOLD
- calculateSplit: allocates total * pct/100 for first n-1; remainder to last

---

## User
```
String id, name, contact, email

getters
```

---

## Expenses
```
String id
Double amount
String paidByUserId
LocalDateTime timestamp
String description
String groupId                  // nullable — null for personal expenses
SplitType splitType
List<Split> splits              // immutable copy (Collections.unmodifiableList)

getters
```

---

## BalanceSheet
```
ConcurrentHashMap<String, ConcurrentHashMap<String, Double>> balances
    // outer key: userId, inner key: counterpartyId, value: net balance

void updateBalances(String fromUser, String toUser, Double amount)
    // fromUser → toUser: merge(+amount)
    // toUser → fromUser: merge(-amount)   — bidirectional symmetric update

void settleBalances(String fromUser, String toUser, Double amount)
    // calls updateBalances(-amount) — reverses the debt

ConcurrentHashMap<String, Double> getBalancesForUser(String userId)
Double getBalance(String user1, String user2)
```

---

## Group
```
String id, groupName
List<String> userIds        // CopyOnWriteArrayList
List<String> expenseIds     // CopyOnWriteArrayList

void addUser(String userId)       // throws if already a member
void addExpense(String expenseId) // throws if already added
getters
```

---

## Splitwise                                        // Singleton pattern
```
BalanceSheet balanceSheet
ConcurrentHashMap<String, User> userMap
ConcurrentHashMap<String, Group> groupMap
ConcurrentHashMap<String, Expenses> expensesMap
Map<SplitType, SplitStrategyInterface> strategies   // HashMap, wired at construction

static getInstance()                // double-checked locking

void addExpense(String id, String userId, Double amount, String description,
                List<Split> splits, SplitType splitType, String groupId)
    // validate user + group exist
    // strategy.validateSplit → strategy.calculateSplit (sets amounts on splits)
    // create Expenses → store in expensesMap
    // if groupId: group.addExpense
    // for each split: balanceSheet.updateBalances(paidByUserId, split.userId, split.amount)

void settleBalance(String fromUserId, String toUserId, Double settleAmount)
    // validate both users → balanceSheet.settleBalances

void addUser(User)            // throws if already exists
void addGroup(Group)          // throws if already exists
void addMemberToGroup(String groupId, String userId)

void showBalancesForUser(String userId)
    // iterates getBalancesForUser → prints:
    //   positive balance → "counterparty owes user RS X"
    //   negative balance → "user owes counterparty RS X"
```

---

## Key Concurrency Notes
| Scenario | Solution |
|---|---|
| Concurrent balance updates for same user pair | `ConcurrentHashMap.merge` in `updateBalances` — atomic merge via `Double::sum` |
| New user pair first-time initialization | `computeIfAbsent` creates inner map atomically |
| Group membership and expense lists | `CopyOnWriteArrayList` — safe concurrent iteration + add |
| All top-level maps in Splitwise | `ConcurrentHashMap` |

---

## Flow Summary
- **Add expense:** Splitwise.addExpense → validate → strategy.validateSplit + calculateSplit → create Expenses → updateBalances for each split (payer credited, each participant debited)
- **Settle:** Splitwise.settleBalance → BalanceSheet.settleBalances → updateBalances(-amount) — reduces debt bidirectionally
- **View:** showBalancesForUser → getBalancesForUser → print positive (owed to you) and negative (you owe) entries