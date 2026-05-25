package Elevator;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

enum Direction {
    UP, DOWN, IDLE
}

interface ElevatorState {
    void addRequest(ElevatorCar car, int floor);

    void step(ElevatorCar car);

    Direction getDirection();
}

class IdleState implements ElevatorState {
    @Override
    public void addRequest(ElevatorCar car, int floor) {
        if (floor > car.getCurrentFloor()) {
            car.getUpQueue().offer(floor);
            car.setState(new MovingUpState());
        } else if (floor < car.getCurrentFloor()) {
            car.getDownQueue().offer(floor);
            car.setState(new MovingDownState());
        }
        // floor == currentFloor → already here, open doors (no-op in simulation)
    }

    @Override
    public void step(ElevatorCar car) {
        /* idle — nothing to do */ }

    @Override
    public Direction getDirection() {
        return Direction.IDLE;
    }
}

class MovingUpState implements ElevatorState {
    @Override
    public void addRequest(ElevatorCar car, int floor) {
        if (floor >= car.getCurrentFloor())
            car.getUpQueue().offer(floor); // on the way up — service it
        else
            car.getDownQueue().offer(floor); // below us — handle on the way back down
    }

    @Override
    public void step(ElevatorCar car) {
        car.setCurrentFloor(car.getCurrentFloor() + 1);
        System.out.println("[Elevator " + car.getId() + "] ↑ Floor " + car.getCurrentFloor());

        if (!car.getUpQueue().isEmpty() && car.getUpQueue().peek() == car.getCurrentFloor()) {
            car.getUpQueue().poll();
            System.out.println("[Elevator " + car.getId() + "] 🚪 Doors open at Floor " + car.getCurrentFloor());
        }

        // Up queue exhausted — switch direction or go idle
        if (car.getUpQueue().isEmpty()) {
            if (!car.getDownQueue().isEmpty())
                car.setState(new MovingDownState());
            else
                car.setState(new IdleState());
        }
    }

    @Override
    public Direction getDirection() {
        return Direction.UP;
    }
}

class MovingDownState implements ElevatorState {
    @Override
    public void addRequest(ElevatorCar car, int floor) {
        if (floor <= car.getCurrentFloor())
            car.getDownQueue().offer(floor); // on the way down — service it
        else
            car.getUpQueue().offer(floor); // above us — handle on the way back up
    }

    @Override
    public void step(ElevatorCar car) {
        car.setCurrentFloor(car.getCurrentFloor() - 1);
        System.out.println("[Elevator " + car.getId() + "] ↓ Floor " + car.getCurrentFloor());

        if (!car.getDownQueue().isEmpty() && car.getDownQueue().peek() == car.getCurrentFloor()) {
            car.getDownQueue().poll();
            System.out.println("[Elevator " + car.getId() + "] 🚪 Doors open at Floor " + car.getCurrentFloor());
        }

        // Down queue exhausted — switch direction or go idle
        if (car.getDownQueue().isEmpty()) {
            if (!car.getUpQueue().isEmpty())
                car.setState(new MovingUpState());
            else
                car.setState(new IdleState());
        }
    }

    @Override
    public Direction getDirection() {
        return Direction.DOWN;
    }
}

class ElevatorCar {
    private final int id;
    private int currentFloor;
    private ElevatorState state;
    private final PriorityQueue<Integer> upQueue; // min-heap — lowest floor visited first ↑
    private final PriorityQueue<Integer> downQueue; // max-heap — highest floor visited first ↓

    public ElevatorCar(int id, int startFloor) {
        this.id = id;
        this.currentFloor = startFloor;
        this.state = new IdleState();
        this.upQueue = new PriorityQueue<>(); // natural order
        this.downQueue = new PriorityQueue<>((a, b) -> b - a); // reverse order
    }

    public void addRequest(int floor) {
        state.addRequest(this, floor);
    }

    public void step() {
        state.step(this);
    }

    public int getId() {
        return id;
    }

    public int getCurrentFloor() {
        return currentFloor;
    }

    public void setCurrentFloor(int floor) {
        this.currentFloor = floor;
    }

    public ElevatorState getState() {
        return state;
    }

    public void setState(ElevatorState state) {
        this.state = state;
    }

    public PriorityQueue<Integer> getUpQueue() {
        return upQueue;
    }

    public PriorityQueue<Integer> getDownQueue() {
        return downQueue;
    }

    public int pendingRequests() {
        return upQueue.size() + downQueue.size();
    }
}

interface DispatchStrategy {
    ElevatorCar selectElevator(List<ElevatorCar> elevators, int floor, Direction direction);
}

class OptimalDispatchStrategy implements DispatchStrategy {
    @Override
    public ElevatorCar selectElevator(List<ElevatorCar> elevators, int floor, Direction direction) {
        ElevatorCar best = null;
        int bestScore = Integer.MAX_VALUE;
        for (ElevatorCar car : elevators) {
            int score = score(car, floor, direction);
            if (score < bestScore) {
                bestScore = score;
                best = car;
            }
        }
        return best;
    }

    private int score(ElevatorCar car, int floor, Direction direction) {
        Direction carDir = car.getState().getDirection();
        int distance = Math.abs(car.getCurrentFloor() - floor);

        // Idle elevator — just distance
        if (carDir == Direction.IDLE)
            return distance;

        // Moving same direction and hasn't passed the floor yet — ideal
        if (carDir == direction) {
            if (carDir == Direction.UP && car.getCurrentFloor() <= floor)
                return distance;
            if (carDir == Direction.DOWN && car.getCurrentFloor() >= floor)
                return distance;
        }

        // Moving opposite direction or already passed — penalise
        return distance + 100;
    }
}

public class Elevator {
    private static volatile Elevator instance;

    private final List<ElevatorCar> elevators;
    private volatile DispatchStrategy dispatchStrategy;

    private Elevator(int numElevators) {
        elevators = new ArrayList<>();
        for (int i = 1; i <= numElevators; i++)
            elevators.add(new ElevatorCar(i, 0 /* ground floor */));
        dispatchStrategy = new OptimalDispatchStrategy();
    }

    public static void initialize(int numElevators) {
        if (instance == null)
            synchronized (Elevator.class) {
                if (instance == null)
                    instance = new Elevator(numElevators);
            }
    }

    public static Elevator getInstance() {
        if (instance == null)
            throw new IllegalStateException("Elevator not initialized. Call initialize() first.");
        return instance;
    }

    // External request — someone on a floor presses ↑ or ↓
    public void requestElevator(int floor, Direction direction) {
        ElevatorCar car = dispatchStrategy.selectElevator(elevators, floor, direction);
        System.out.println("[Controller] Dispatching Elevator " + car.getId() + " → Floor " + floor);
        car.addRequest(floor);
    }

    // Internal request — passenger inside presses a floor button
    public void requestFloor(int elevatorId, int floor) {
        elevators.stream()
                .filter(car -> car.getId() == elevatorId)
                .findFirst()
                .ifPresent(car -> car.addRequest(floor));
    }

    public void setDispatchStrategy(DispatchStrategy strategy) {
        this.dispatchStrategy = strategy;
    }

    // Simulation tick — advance all elevators one floor
    public void step() {
        for (ElevatorCar car : elevators)
            car.step();
    }

    // ── Demo ─────────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        Elevator.initialize(2); // 2 elevators, both start at floor 0
        Elevator elevator = Elevator.getInstance();

        // External requests — hall buttons
        elevator.requestElevator(5, Direction.UP);  // Elevator 1 dispatched to floor 5
        elevator.requestElevator(3, Direction.DOWN); // Elevator 2 dispatched to floor 3

        // Internal requests — floor buttons pressed inside each car
        elevator.requestFloor(1, 8); // Elevator 1 passenger wants floor 8
        elevator.requestFloor(2, 1); // Elevator 2 passenger wants floor 1

        System.out.println("\n── Simulation ticks ──");
        for (int tick = 0; tick < 10; tick++)
            elevator.step();
    }
}
