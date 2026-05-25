# Elevator — Entities

---

## Enums
```
Direction: UP, DOWN, IDLE
```

---

## interface ElevatorState                         // State pattern
```
void addRequest(ElevatorCar car, int floor)
void step(ElevatorCar car)
Direction getDirection()
```

### IdleState
- `addRequest`: floor > current → offer to upQueue, transition to MovingUpState
                floor < current → offer to downQueue, transition to MovingDownState
                floor == current → no-op (already here)
- `step`: no-op

### MovingUpState
- `addRequest`: floor >= current → upQueue (on the way); floor < current → downQueue (on return)
- `step`: currentFloor++
          → if upQueue.peek() == currentFloor → poll (stop, open doors)
          → if upQueue empty: downQueue non-empty → MovingDownState; else → IdleState

### MovingDownState
- `addRequest`: floor <= current → downQueue (on the way); floor > current → upQueue (on return)
- `step`: currentFloor--
          → if downQueue.peek() == currentFloor → poll (stop, open doors)
          → if downQueue empty: upQueue non-empty → MovingUpState; else → IdleState

---

## ElevatorCar
```
int id
int currentFloor
ElevatorState state
PriorityQueue<Integer> upQueue    // min-heap — lowest floor first going up
PriorityQueue<Integer> downQueue  // max-heap — highest floor first going down

void addRequest(int floor)        // delegates to state.addRequest
void step()                       // delegates to state.step
int pendingRequests()             // upQueue.size() + downQueue.size()

getters + setters for currentFloor, state, upQueue, downQueue
```

---

## interface DispatchStrategy                      // Strategy pattern
```
ElevatorCar selectElevator(List<ElevatorCar> elevators, int floor, Direction direction)
```

### OptimalDispatchStrategy
Scores each elevator and picks the lowest score:
```
score(car, floor, direction):
    distance = |car.currentFloor - floor|

    IDLE car             → score = distance
    same direction, not yet passed floor → score = distance      ← ideal
    opposite direction or already passed → score = distance + 100 ← penalised
```

---

## Elevator                                        // Singleton (Controller/Facade)
```
volatile DispatchStrategy dispatchStrategy
List<ElevatorCar> elevators

static initialize(int numElevators)               // double-checked locking
static getInstance()                              // throws if not initialized

void requestElevator(int floor, Direction direction)
    // external request — hall button pressed
    // selectElevator → car.addRequest(floor)

void requestFloor(int elevatorId, int floor)
    // internal request — passenger presses floor button inside car
    // find car by id → car.addRequest(floor)

void setDispatchStrategy(DispatchStrategy)

void step()
    // simulation tick — advances all elevators one floor
    // for each car: car.step()
```

---

## SCAN Algorithm (Elevator Algorithm)
```
Each ElevatorCar maintains two queues:
  upQueue   (min-heap) — floors to visit while going UP, lowest first
  downQueue (max-heap) — floors to visit while going DOWN, highest first

Moving UP:
  → increment currentFloor
  → if upQueue.peek() == currentFloor → stop (open doors), poll
  → if upQueue empty → reverse to MovingDownState (or Idle)

Moving DOWN:
  → decrement currentFloor
  → if downQueue.peek() == currentFloor → stop (open doors), poll
  → if downQueue empty → reverse to MovingUpState (or Idle)

New request while moving:
  → same direction and reachable → add to current directional queue
  → opposite direction → add to the return queue (served on the way back)

Why SCAN?
  Avoids starvation — elevator sweeps fully in one direction before reversing.
  Minimises total travel distance vs FCFS.
```

---

## Patterns Used
| Pattern | Where |
|---|---|
| Singleton | Elevator (controller) — single system entry point |
| State | ElevatorState — Idle / MovingUp / MovingDown; addRequest + step behaviour varies by state |
| Strategy | DispatchStrategy — pluggable algorithm for assigning elevators to requests |

---

## Flow Summary
- **External request (hall button):** `Elevator.requestElevator(floor, UP)` → `dispatchStrategy.selectElevator(...)` → `car.addRequest(floor)` → state routes floor to upQueue or downQueue
- **Internal request (floor button):** `Elevator.requestFloor(elevatorId, floor)` → same `car.addRequest(floor)` path
- **Simulation tick:** `Elevator.step()` → each car calls `state.step(car)` → moves one floor, checks queue for a stop, transitions state if queue exhausted
- **Direction reversal:** upQueue empties mid-run → car transitions to MovingDownState automatically, starts draining downQueue from the highest floor down
