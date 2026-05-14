package Uber;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import Uber.Trip.TripBuilder;

enum DriverStatus {
    ONLINE,
    OFFLINE,
    ON_TRIP
}

enum TripStatus {
    REQUESTED, ASSIGNED, STARTED, COMPLETED, CANCELLED
}

enum RideType {
    BIKE, NON_AC_CAB, AC_CAB
}

class Location {
    private final double lat;
    private final double lng;

    public Location(double lat, double lng) {
        this.lat = lat;
        this.lng = lng;
    }

    public double getLat() {
        return lat;
    }

    public double getLng() {
        return lng;
    }

    public double getDistance(Location destination) {
        double dx = this.lat - destination.lat;
        double dy = this.lng - destination.lng;
        return Math.sqrt(dx * dx + dy * dy);
    }

    public String toString() {
        // TODO: returns lat + lang
        return "";
    }
}

class Vehicle {
    private final String licenseNo;
    private final String model;
    private final RideType rideType;

    public Vehicle(String licenseNo, String model, RideType rideType) {
        this.licenseNo = licenseNo;
        this.model = model;
        this.rideType = rideType;
    }

    public String getLicenseNo() {
        return licenseNo;
    }

    public String getModel() {
        return model;
    }

    public RideType getRideType() {
        return rideType;
    }
}

interface TripObserver {
    public void onUpdate(Trip trip);
}

abstract class User implements TripObserver {
    private final String id;
    private final String name;
    private final String contact;
    private final List<Trip> tripHistory;

    public User(String name, String contact) {
        id = UUID.randomUUID().toString();
        this.name = name;
        this.contact = contact;
        tripHistory = new CopyOnWriteArrayList<>();
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

    public List<Trip> getTripHistory() {
        return Collections.unmodifiableList(tripHistory);
    }

    public void addTripToHistory(Trip trip) {
        tripHistory.add(trip);
    }
}

class Rider extends User {
    public Rider(String name, String contact) {
        super(name, contact);
    }

    public void onUpdate(Trip trip) {
        System.out.println("Trip has been " + trip.getTripStatus().toString());
    }
}

class Driver extends User {
    private Vehicle vehicle;
    private Location currentLocation;
    private DriverStatus driverStatus;

    public Driver(String name, String contact, Vehicle vehicle) {
        super(name, contact);
        this.vehicle = vehicle;
        this.currentLocation = null;
        driverStatus = DriverStatus.OFFLINE;
    }

    public void onUpdate(Trip trip) {
        if (trip.getTripStatus().equals(TripStatus.REQUESTED))
            System.out.println("New ride available for you to accept: " + trip.getId() + " from "
                    + trip.getSource().toString() + " to " + trip.getDestination().toString());
        else if (trip.getTripStatus().equals(TripStatus.CANCELLED))
            System.out.println("Rider cancelled the trip");
    }

    public Vehicle getVehicle() {
        return vehicle;
    }

    public void setVehicle(Vehicle vehicle) {
        this.vehicle = vehicle;
    }

    public Location getCurrentLocation() {
        return currentLocation;
    }

    public void setCurrentLocation(Location currentLocation) {
        this.currentLocation = currentLocation;
    }

    public DriverStatus getDriverStatus() {
        return driverStatus;
    }

    public void setDriverStatus(DriverStatus driverStatus) {
        this.driverStatus = driverStatus;
    }
}

interface FareCalculationStrategy {
    public double calculateFare(Location source, Location destination, RideType rideType);

}

class FixedRateFareCalculationStrategy implements FareCalculationStrategy {
    private final double fixedRate;

    public FixedRateFareCalculationStrategy(double fixedRate) {
        this.fixedRate = fixedRate;
    }

    @Override
    public double calculateFare(Location source, Location destination, RideType rideType) {
        return (source.getDistance(destination)) * fixedRate;
    }
}

interface DriverFindingStrategy {
    public List<Driver> getDrivers(List<Driver> drivers, Location source, RideType rideType);

}

class NearestDriverStrategy implements DriverFindingStrategy {
    private double thresholdRadius;

    public NearestDriverStrategy(double thresholdRadius) {
        this.thresholdRadius = thresholdRadius;
    }

    @Override
    public List<Driver> getDrivers(List<Driver> drivers, Location source, RideType rideType) {
        return drivers.stream().filter(driver -> driver.getDriverStatus().equals(DriverStatus.ONLINE))
                .filter(driver -> driver.getVehicle().getRideType().equals(rideType))
                .filter(driver -> driver.getCurrentLocation().getDistance(source) <= thresholdRadius)
                .sorted(Comparator
                        .comparingDouble(driver -> driver.getCurrentLocation().getDistance(source)))
                .toList();
    }

}

interface TripState {
    public void requestTrip(Trip trip);

    public void assignTrip(Trip trip, Driver driver);

    public void startTrip(Trip trip);

    public void endTrip(Trip trip);

    public void rejectTrip(Trip trip);

    public void cancelTrip(Trip trip);

}

class RequestedTripState implements TripState {
    @Override
    public void requestTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been requested");
    }

    @Override
    public void assignTrip(Trip trip, Driver driver) {
        trip.setDriver(driver);
        trip.addObserver(driver);
        trip.setCurrentTripState(new AssignedTripState());
        trip.setTripStatus(TripStatus.ASSIGNED);
        driver.setDriverStatus(DriverStatus.ON_TRIP);
        for (TripObserver tripObserver : trip.getTripObservers())
            tripObserver.onUpdate(trip);
    }

    @Override
    public void startTrip(Trip trip) {
        throw new IllegalStateException("Driver not assigned yet");
    }

    @Override
    public void endTrip(Trip trip) {
        throw new IllegalStateException("Ride started yet");
    }

    @Override
    public void rejectTrip(Trip trip) {
    }

    @Override
    public void cancelTrip(Trip trip) {
        trip.setTripStatus(TripStatus.CANCELLED);
        trip.setCurrentTripState(new CancelledTripState());
        for (TripObserver tripObserver : trip.getTripObservers())
            tripObserver.onUpdate(trip);
    }

}

class AssignedTripState implements TripState {
    @Override
    public void requestTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been requested");
    }

    @Override
    public void assignTrip(Trip trip, Driver driver) {
        throw new IllegalStateException("Ride has already been assigned");
    }

    @Override
    public void startTrip(Trip trip) {
        trip.setTripStatus(TripStatus.STARTED);
        trip.setCurrentTripState(new StartedTripState());
        for (TripObserver tripObserver : trip.getTripObservers())
            tripObserver.onUpdate(trip);
    }

    @Override
    public void endTrip(Trip trip) {
        throw new IllegalStateException("Ride has not started yet");
    }

    @Override
    public void rejectTrip(Trip trip) {
    }

    @Override
    public void cancelTrip(Trip trip) {
        trip.setTripStatus(TripStatus.CANCELLED);
        trip.setCurrentTripState(new CancelledTripState());
        for (TripObserver tripObserver : trip.getTripObservers())
            tripObserver.onUpdate(trip);
    }

}

class StartedTripState implements TripState {
    @Override
    public void requestTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been requested");
    }

    @Override
    public void assignTrip(Trip trip, Driver driver) {
        throw new IllegalStateException("Ride has already been assigned");
    }

    @Override
    public void startTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been started");
    }

    @Override
    public void endTrip(Trip trip) {
        trip.setTripStatus(TripStatus.COMPLETED);
        trip.setCurrentTripState(new CompletedTripState());
        trip.getDriver().setDriverStatus(DriverStatus.ONLINE);
        trip.getDriver().addTripToHistory(trip);
        trip.getRider().addTripToHistory(trip);
        for (TripObserver tripObserver : trip.getTripObservers())
            tripObserver.onUpdate(trip);
    }

    @Override
    public void rejectTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been started");
    }

    @Override
    public void cancelTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been started");
    }

}

class CompletedTripState implements TripState {
    @Override
    public void requestTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been completed");
    }

    @Override
    public void assignTrip(Trip trip, Driver driver) {
        throw new IllegalStateException("Ride has already been completed");
    }

    @Override
    public void startTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been completed");
    }

    @Override
    public void endTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been completed");
    }

    @Override
    public void rejectTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been completed");
    }

    @Override
    public void cancelTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been completed");
    }

}

class CancelledTripState implements TripState {
    @Override
    public void requestTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been cancelled");
    }

    @Override
    public void assignTrip(Trip trip, Driver driver) {
        throw new IllegalStateException("Ride has already been cancelled");
    }

    @Override
    public void startTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been cancelled");

    }

    @Override
    public void endTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been cancelled");

    }

    @Override
    public void rejectTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been cancelled");

    }

    @Override
    public void cancelTrip(Trip trip) {
        throw new IllegalStateException("Ride has already been cancelled");
    }

}

class Trip {
    private final String id;
    private final Rider rider;
    private Driver driver;
    private final Location source;
    private final Location destination;
    private volatile TripState currentTripState;
    private volatile TripStatus tripStatus;
    private final double fare;
    private final List<TripObserver> tripObservers;
    private Integer riderRating;
    private Integer driverRating;

    private Trip(TripBuilder builder) {
        this.id = builder.id;
        this.rider = builder.rider;
        this.source = builder.source;
        this.destination = builder.destination;
        this.fare = builder.fare;
        tripStatus = TripStatus.REQUESTED;
        currentTripState = new RequestedTripState();
        tripObservers = new CopyOnWriteArrayList<>();
        riderRating = null;
        driverRating = null;
    }

    public void requestTrip() {
        currentTripState.requestTrip(this);
    }

    public synchronized void assignTrip(Driver driver) {
        currentTripState.assignTrip(this, driver);
    }

    public synchronized void startTrip() {
        currentTripState.startTrip(this);
    }

    public synchronized void endTrip() {
        currentTripState.endTrip(this);
    }

    public synchronized void rejectTrip() {
        currentTripState.rejectTrip(this);

    }

    public synchronized void cancelTrip() {
        currentTripState.cancelTrip(this);

    }

    public static class TripBuilder {
        private String id;
        private Rider rider;
        private Location source;
        private Location destination;
        private double fare;

        public TripBuilder() {
            id = UUID.randomUUID().toString().substring(0, 8);
        }

        public TripBuilder withRider(Rider rider) {
            this.rider = rider;
            return this;
        }

        public TripBuilder withSource(Location source) {
            this.source = source;
            return this;
        }

        public TripBuilder withDestination(Location destination) {
            this.destination = destination;
            return this;
        }

        public TripBuilder withFare(double fare) {
            this.fare = fare;
            return this;
        }

        public Trip build() {
            return new Trip(this);
        }
    }

    public String getId() {
        return id;
    }

    public Rider getRider() {
        return rider;
    }

    public Driver getDriver() {
        return driver;
    }

    public Location getSource() {
        return source;
    }

    public Location getDestination() {
        return destination;
    }

    public TripState getCurrentTripState() {
        return currentTripState;
    }

    public TripStatus getTripStatus() {
        return tripStatus;
    }

    public double getFare() {
        return fare;
    }

    public void addObserver(TripObserver observer) {
        tripObservers.add(observer);
    }

    public void removeObserver(TripObserver observer) {
        tripObservers.remove(observer);
    }

    public Integer getRiderRating() {
        return riderRating;
    }

    public void setRiderRating(Integer riderRating) {
        this.riderRating = riderRating;
    }

    public Integer getDriverRating() {
        return driverRating;
    }

    public void setDriverRating(Integer driverRating) {
        this.driverRating = driverRating;
    }

    public void setDriver(Driver driver) {
        this.driver = driver;
    }

    public void setCurrentTripState(TripState currentTripState) {
        this.currentTripState = currentTripState;
    }

    public List<TripObserver> getTripObservers() {
        return tripObservers;
    }

    public void setTripStatus(TripStatus tripStatus) {
        this.tripStatus = tripStatus;
    }
}

public class Uber {
    private static volatile Uber instance;
    private final Map<String, Rider> riderMap;
    private final Map<String, Driver> driverMap;
    private DriverFindingStrategy driverFindingStrategy;
    private FareCalculationStrategy fareCalculationStrategy;
    private final Map<String, Trip> activeTrips;

    private Uber() {
        riderMap = new ConcurrentHashMap<>();
        driverMap = new ConcurrentHashMap<>();
        driverFindingStrategy = new NearestDriverStrategy(3);
        fareCalculationStrategy = new FixedRateFareCalculationStrategy(0.5);
        activeTrips = new ConcurrentHashMap<>();

    }

    public static Uber getInstance() {
        if (instance == null)
            synchronized (Uber.class) {
                if (instance == null)
                    instance = new Uber();
            }
        return instance;
    }

    public void updateDriverLocation(String driverId, Location location) {
        Driver driver = driverMap.getOrDefault(driverId, null);
        if (driver == null)
            throw new IllegalStateException("Driver not found");
        driver.setCurrentLocation(location);
    }

    public void rateDriver(Trip trip, String riderId, Integer rating) {
        if (rating < 0 || rating > 5)
            throw new IllegalStateException("Invalid rating");
        Rider rider = riderMap.getOrDefault(riderId, null);
        if (rider == null)
            throw new IllegalStateException("Rider not found");
        if (!trip.getRider().getId().equals(riderId))
            throw new IllegalStateException("Not authorised to rate someone else's ride");
        trip.setDriverRating(rating);
    }

    public void rateRider(Trip trip, String driverId, Integer rating) {
        if (rating < 0 || rating > 5)
            throw new IllegalStateException("Invalid rating");
        Driver driver = driverMap.getOrDefault(driverId, null);
        if (driver == null)
            throw new IllegalStateException("Driver not found");
        if (!trip.getDriver().getId().equals(driverId))
            throw new IllegalStateException("Not authorised to rate someone else's ride");
        trip.setRiderRating(rating);
    }

    public void bookRide(Rider rider, RideType rideType, Location source, Location destination) {
        rider = riderMap.getOrDefault(rider.getId(), null);
        if (rider == null)
            throw new IllegalStateException("Rider not found");
        double fare = fareCalculationStrategy.calculateFare(source, destination, rideType);
        System.out.println("Fare is " + fare);
        Trip trip = new TripBuilder().withRider(rider).withSource(source).withDestination(destination).withFare(fare)
                .build();
        System.out.println("Ride requested. Finding drivers");
        trip.addObserver(rider);
        rider.onUpdate(trip);
        List<Driver> nearbyDrivers = driverFindingStrategy.getDrivers(List.copyOf(driverMap.values()), source,
                rideType);
        activeTrips.put(trip.getId(), trip);
        for (Driver driver : nearbyDrivers)
            driver.onUpdate(trip);
    }

    public void cancelRide(Rider rider, Trip trip) {
        rider = riderMap.getOrDefault(rider.getId(), null);
        if (rider == null)
            throw new IllegalStateException("Rider not found");
        if (!trip.getRider().equals(rider))
            throw new IllegalStateException("Not authorized to cancel");
        trip.cancelTrip();
        activeTrips.remove(trip.getId());

    }

    public void acceptRide(Driver driver, Trip trip) {
        driver = driverMap.getOrDefault(driver.getId(), null);
        if (driver == null)
            throw new IllegalStateException("Driver not found");
        trip.assignTrip(driver);
    }

    public void rejectRide(Driver driver, Trip trip) {
    }

    public void startRide(Driver driver, Trip trip) {
        driver = driverMap.getOrDefault(driver.getId(), null);
        if (driver == null)
            throw new IllegalStateException("Driver not found");
        if (!driver.equals(trip.getDriver()))
            throw new IllegalStateException("Not authorised to accept");
        trip.startTrip();
    }

    public void endRide(Driver driver, Trip trip) {
        driver = driverMap.getOrDefault(driver.getId(), null);
        if (driver == null)
            throw new IllegalStateException("Driver not found");
        if (!driver.equals(trip.getDriver()))
            throw new IllegalStateException("Not authorised to accept");
        trip.endTrip();
        activeTrips.remove(trip.getId());
    }

    public DriverFindingStrategy getDriverFindingStrategy() {
        return driverFindingStrategy;
    }

    public void setDriverFindingStrategy(DriverFindingStrategy driverFindingStrategy) {
        this.driverFindingStrategy = driverFindingStrategy;
    }

    public FareCalculationStrategy getFareCalculationStrategy() {
        return fareCalculationStrategy;
    }

    public void setFareCalculationStrategy(FareCalculationStrategy fareCalculationStrategy) {
        this.fareCalculationStrategy = fareCalculationStrategy;
    }

    public void markDriverAsOnline(Driver driver) {
        driver = driverMap.getOrDefault(driver.getId(), null);
        if (driver == null)
            throw new IllegalStateException("Driver not found");
        if (driver.getDriverStatus().equals(DriverStatus.ON_TRIP))
            throw new IllegalStateException("Already on a trip");
        driver.setDriverStatus(DriverStatus.ONLINE);
    }

    public void markDriverAsOffline(Driver driver) {
        driver = driverMap.getOrDefault(driver.getId(), null);
        if (driver == null)
            throw new IllegalStateException("Driver not found");
        if (driver.getDriverStatus().equals(DriverStatus.ON_TRIP))
            throw new IllegalStateException("Already on a trip");
        driver.setDriverStatus(DriverStatus.OFFLINE);
    }

}
