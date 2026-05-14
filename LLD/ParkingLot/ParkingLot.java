package ParkingLot;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

enum SpotType {
    SMALL,
    MEDIUM,
    LARGE
}

abstract class Vehicle {
    private final String licensePlate;
    private final SpotType spotType;

    public Vehicle(String licensePlate, SpotType spotType) {
        this.licensePlate = licensePlate;
        this.spotType = spotType;
    }

    public String getLicensePlate() {
        return licensePlate;
    }

    public SpotType getSpotType() {
        return spotType;
    }
}

class Bike extends Vehicle {
    public Bike(String licenePlate) {
        super(licenePlate, SpotType.SMALL);
    }
}

class Car extends Vehicle {
    public Car(String licenePlate) {
        super(licenePlate, SpotType.MEDIUM);
    }
}

class Truck extends Vehicle {
    public Truck(String licenePlate) {
        super(licenePlate, SpotType.LARGE);
    }
}

class ParkingSpot {
    private final String spotId;
    private Vehicle parkedVehicle;
    private final SpotType spotType;

    public ParkingSpot(String spotId, SpotType spotType) {
        this.spotId = spotId;
        this.spotType = spotType;
    }

    public String getSpotId() {
        return spotId;
    }

    public boolean isAvailable() {
        return parkedVehicle == null;
    }

    public boolean canFitVehicle(SpotType spotType) {
        return this.spotType == spotType;
    }

    public SpotType getSpotType() {
        return spotType;
    }

    public Vehicle getVehicle() {
        return parkedVehicle;
    }

    public synchronized void parkVehicle(Vehicle vehicle) {
        if (!isAvailable())
            throw new IllegalStateException("Spot Already Occupied");
        if (!canFitVehicle(vehicle.getSpotType()))
            throw new IllegalArgumentException("Spot can't fit this vehicle");
        parkedVehicle = vehicle;
    }

    public synchronized void unparkVehicle() {
        parkedVehicle = null;
    }
}

class ParkingTicket {
    private final String ticketId;
    private final Vehicle vehicle;
    private final ParkingSpot parkingSpot;
    private final LocalDateTime entryTime;
    private LocalDateTime exitTime;

    public ParkingTicket(String ticketId, Vehicle vehicle, ParkingSpot parkingSpot) {
        this.ticketId = ticketId;
        this.vehicle = vehicle;
        this.parkingSpot = parkingSpot;
        this.entryTime = LocalDateTime.now();
    }

    public String getTicketId() {
        return ticketId;
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public ParkingSpot getParkingSpot() {
        return parkingSpot;
    }

    public LocalDateTime getEntryTime() {
        return entryTime;
    }

    public LocalDateTime getExitTime() {
        return exitTime;
    }

    public void setExitTime(LocalDateTime exitTime) {
        this.exitTime = exitTime;
    }
}

class ParkingFloor {
    private final Integer floorNumber;
    private final Map<SpotType, List<ParkingSpot>> parkingSpotMap;

    public ParkingFloor(Integer floorNumber, Map<SpotType, Integer> spotCountMap) {
        this.floorNumber = floorNumber;
        this.parkingSpotMap = initialize(spotCountMap);
    }

    public Map<SpotType, List<ParkingSpot>> initialize(Map<SpotType, Integer> spotCountMap) {
        Map<SpotType, List<ParkingSpot>> spotMap = new HashMap<>();
        for (SpotType spotType : SpotType.values()) {
            int count = spotCountMap.getOrDefault(spotType, 0);
            List<ParkingSpot> parkingSpots = new ArrayList<>();
            for (int i = 0; i < count; i++)
                parkingSpots.add(new ParkingSpot(getSpotId(spotType, i), spotType));
            spotMap.put(spotType, parkingSpots);
        }
        return spotMap;
    }

    public String getSpotId(SpotType spotType, Integer count) {
        return floorNumber.toString() + "-" + spotType.toString().substring(0, 1) + "-" + count.toString();
    }

    public ParkingSpot getAvailableSpot(SpotType spotType) {
        for (ParkingSpot spot : parkingSpotMap.get(spotType))
            if (spot.isAvailable())
                return spot;
        return null;
    }

    public Map<SpotType, List<ParkingSpot>> getParkingSpotMap() {
        return parkingSpotMap;
    }

    public int getFloorNumber() {
        return floorNumber;
    }

    public List<ParkingSpot> getParkingSpotsForType(SpotType spotType) {
        return parkingSpotMap.get(spotType);
    }
}

interface FeesStrategy {
    public double getFees(ParkingTicket ticket);
}

class FixedRateStrategy implements FeesStrategy {
    private final double fixedRate;

    public FixedRateStrategy(double fixedRate) {
        this.fixedRate = fixedRate;
    }

    public double getFees(ParkingTicket ticket) {
        return fixedRate;
    }
}

class ParkingManager {
    private FeesStrategy feesStrategy;
    private Map<String, ParkingTicket> activeTickets;
    private Map<SpotType, Queue<ParkingSpot>> availableSpotsMap;
    private List<ParkingFloor> parkingFloors;

    public ParkingManager(List<ParkingFloor> parkingFloors, FeesStrategy feesStrategy) {
        this.feesStrategy = feesStrategy;
        this.parkingFloors = parkingFloors;
        this.activeTickets = new ConcurrentHashMap<>();
        this.availableSpotsMap = new ConcurrentHashMap<>();

        for (SpotType spotType : SpotType.values()) {
            availableSpotsMap.put(spotType, new ConcurrentLinkedQueue<>());
        }

        for (ParkingFloor floor : parkingFloors)
            for (SpotType spotType : SpotType.values())
                for (ParkingSpot spot : floor.getParkingSpotMap().get(spotType)) {
                    availableSpotsMap.get(spotType).add(spot);
                }
    }

    public FeesStrategy getFeesStrategy() {
        return feesStrategy;
    }

    public ParkingTicket parkVehicle(Vehicle vehicle) {
        while (true) {
            ParkingSpot spot = availableSpotsMap.get(vehicle.getSpotType()).poll();
            if (spot == null)
                throw new IllegalStateException("No Spot for vehicle to park");
            try {
                spot.parkVehicle(vehicle);
                String ticketId = UUID.randomUUID().toString().substring(0, 8);
                ParkingTicket ticket = new ParkingTicket(ticketId, vehicle, spot);
                activeTickets.put(ticketId, ticket);
                return ticket;
            } catch (IllegalStateException e) {
                continue;
            }
        }
    }

    public double unparkVehicle(String ticketId) {
        ParkingTicket ticket = activeTickets.getOrDefault(ticketId, null);
        if (ticket == null)
            throw new IllegalStateException("Ticket ID not found");
        ticket.setExitTime(LocalDateTime.now());
        double fees = feesStrategy.getFees(ticket);
        ticket.getParkingSpot().unparkVehicle();
        activeTickets.remove(ticketId);
        availableSpotsMap.get(ticket.getVehicle().getSpotType()).add(ticket.getParkingSpot());
        return fees;
    }

    public void setFeesStrategy(FeesStrategy feesStrategy) {
        this.feesStrategy = feesStrategy;
    }

    public void addFloor(ParkingFloor floor) {
        parkingFloors.add(floor);
    }
}