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

void simplifyDebts(String groupId)
    // Step 1 — net balance per user:
    //   for each user in group, sum their BalanceSheet row across group members
    //   positive = net creditor, negative = net debtor
    // Step 2 — two max-heaps:
    //   creditors PQ: sorted by largest positive balance
    //   debtors PQ:   sorted by most negative balance
    // Step 3 — greedy settle loop:
    //   poll top creditor + top debtor
    //   amount = min(creditor balance, |debtor balance|)
    //   print: "debtor pays creditor RS amount"
    //   net.merge to update both balances
    //   re-offer to heap if balance still non-zero (|val| > 0.01)
    //   repeat until both heaps empty
    // Does NOT write back to BalanceSheet — prints minimum transactions only
```

---

## Debt Simplification Algorithm
```
Goal: given N users with arbitrary bilateral debts, produce the minimum
      number of transactions to settle all balances.

Key insight: bilateral debt chains (A→B→C) can always be short-circuited.
             What matters is each person's NET position, not who owes whom.

Step 1 — Net balance
  For each user: net = sum of balances[user][*] across group members
  net > 0 → creditor (others owe this person)
  net < 0 → debtor (this person owes others)

Step 2 — Max-heaps
  creditors PQ: largest net positive at top
  debtors PQ:   most negative net at top

Step 3 — Greedy loop  (O(n log n))
  poll maxCreditor, poll maxDebtor
  settle = min(creditor.net, |debtor.net|)
  → at least one of them reaches exactly 0 each round
  → re-queue the other if remainder > threshold (0.01)
  → repeat until both heaps empty

Why this is optimal:
  Each iteration eliminates at least one person's balance entirely.
  You cannot do fewer than (non-zero-balance people - 1) transactions,
  and this algorithm achieves exactly that in the best case.

Example:
  A net +30, B net -10, C net -20
  Round 1: A(+30) vs C(-20) → C pays A 20 → A now +10, C done
  Round 2: A(+10) vs B(-10) → B pays A 10 → all done
  Result: 2 transactions (vs up to 4 with naive bilateral settlement)
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