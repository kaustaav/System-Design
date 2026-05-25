package BookMyShow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

enum SeatType {
    CLASSIC(200), REGULAR(100), PREMIUM(500);

    private final int price;

    SeatType(int price) {
        this.price = price;
    }

    public int getPrice() {
        return price;
    }
}

enum BookingStatus {
    INITIATED, CONFIRMED, CANCELLED, FAILED
}

enum SeatStatus {
    AVAILABLE, LOCKED, BOOKED
}

enum PaymentMethod {
    CREDIT_CARD
}

class Movie {
    private final String id;
    private final String name;
    private final LocalDate releaseDate;

    public Movie(String name, LocalDate releaseDate) {
        id = UUID.randomUUID().toString().substring(0, 8);
        this.name = name;
        this.releaseDate = releaseDate;
    }

    public String getName() {
        return name;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public String getId() {
        return id;
    }
}

class Seat {
    private final String id;
    private final String seatNo;
    private final SeatType seatType;

    public Seat(String id, String seatNo, SeatType seatType) {
        this.id = id;
        this.seatNo = seatNo;
        this.seatType = seatType;
    }

    public String getId() {
        return id;
    }

    public String getSeatNo() {
        return seatNo;
    }

    public SeatType getSeatType() {
        return seatType;
    }
}

class Screen {
    private final String id;
    private final List<Seat> seats;

    public Screen(String id, List<Seat> seats) {
        this.id = id;
        this.seats = new CopyOnWriteArrayList<>(seats);
    }

    public String getId() {
        return id;
    }

    public List<Seat> getSeats() {
        return seats;
    }

    public void addSeat(Seat seat) {
        seats.add(seat);
    }
}

class Theatre {
    private final String id;
    private final String name;
    private final List<Screen> screens;

    public Theatre(String id, String name, List<Screen> screens) {
        this.id = id;
        this.name = name;
        this.screens = new CopyOnWriteArrayList<>(screens);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<Screen> getScreens() {
        return screens;
    }

    public void addScreen(Screen screen) {
        screens.add(screen);
    }
}

class ShowSeat {
    private final Seat seat;
    private volatile SeatStatus seatStatus;

    public ShowSeat(Seat seat) {
        this.seat = seat;
        seatStatus = SeatStatus.AVAILABLE;
    }

    public Seat getSeat() {
        return seat;
    }

    public SeatStatus getSeatStatus() {
        return seatStatus;
    }

    public void setSeatStatus(SeatStatus seatStatus) {
        this.seatStatus = seatStatus;
    }

    public int getSeatPrice() {
        return seat.getSeatType().getPrice();
    }
}

class Show {
    private final String id;
    private final Movie movie;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;
    private final Theatre theatre;
    private final Screen screen;
    private final Map<String, ShowSeat> showSeats;

    public Show(Movie movie, Theatre theatre, LocalDateTime startTime, LocalDateTime endTime, Screen screen) {
        id = UUID.randomUUID().toString().substring(0, 8);
        this.movie = movie;
        this.startTime = startTime;
        this.theatre = theatre;
        this.endTime = endTime;
        this.screen = screen;
        showSeats = new ConcurrentHashMap<>();
        initializeShowSeats();
    }

    private void initializeShowSeats() {
        for (Seat seat : screen.getSeats())
            showSeats.put(seat.getSeatNo(), new ShowSeat(seat));
    }

    public String getId() {
        return id;
    }

    public Movie getMovie() {
        return movie;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public Screen getScreen() {
        return screen;
    }

    public Map<String, ShowSeat> getShowSeats() {
        return showSeats;
    }

    public Theatre getTheatre() {
        return theatre;
    }
}

class Booking {
    private final String id;
    private final Show show;
    private volatile BookingStatus bookingStatus;
    private double charge;
    private final List<ShowSeat> showSeats;

    public Booking(Show show, List<ShowSeat> showSeats) {
        id = UUID.randomUUID().toString().substring(0, 8);
        this.show = show;
        bookingStatus = BookingStatus.INITIATED;
        this.showSeats = showSeats;
    }

    public String getId() {
        return id;
    }

    public Show getShow() {
        return show;
    }

    public BookingStatus getBookingStatus() {
        return bookingStatus;
    }

    public void setBookingStatus(BookingStatus bookingStatus) {
        this.bookingStatus = bookingStatus;
    }

    public double getCharge() {
        return charge;
    }

    public void setCharge(double charge) {
        this.charge = charge;
    }

    public List<ShowSeat> getShowSeats() {
        return Collections.unmodifiableList(showSeats);
    }
}

interface PaymentStrategy {
    public void payCharge(Booking booking);

}

class CreditCardPaymentStrategy implements PaymentStrategy {
    @Override
    public void payCharge(Booking booking) {
        System.out.println("Paid Rs: " + booking.getCharge() + " via Credit Card");
    }

}

interface ChargingStrategy {
    public double getCharge(Booking booking);
}

class FixedRateChargingStrategy implements ChargingStrategy {
    @Override
    public double getCharge(Booking booking) {
        double totalCharge = 0.0d;
        for (ShowSeat showSeat : booking.getShowSeats())
            totalCharge += showSeat.getSeatPrice();
        return totalCharge;
    }
}

class BookingService {
    private final Map<String, ReentrantLock> showLockMap;

    public BookingService() {
        showLockMap = new ConcurrentHashMap<>();
    }

    public Booking bookTickets(Show show, List<ShowSeat> showSeats, PaymentMethod paymentMethod) {
        ReentrantLock lock = showLockMap.computeIfAbsent(show.getId(), e -> new ReentrantLock());
        lock.lock();
        try {
            for (ShowSeat showSeat : showSeats)
                if (!showSeat.getSeatStatus().equals(SeatStatus.AVAILABLE))
                    throw new IllegalStateException("Seat no longer available. Please retry");
            for (ShowSeat showSeat : showSeats)
                showSeat.setSeatStatus(SeatStatus.LOCKED);
            Booking booking = new Booking(show, showSeats);
            return booking;

        } finally {
            lock.unlock();
        }
    }

    public void cancelBooking(Booking booking) {
        for (ShowSeat showSeat : booking.getShowSeats())
            showSeat.setSeatStatus(SeatStatus.AVAILABLE);
        booking.setBookingStatus(BookingStatus.CANCELLED);
    }

}

class PaymentFactory {
    public static PaymentStrategy getPaymentStrategy(PaymentMethod paymentMethod) {
        switch (paymentMethod) {
            case PaymentMethod.CREDIT_CARD:
                return new CreditCardPaymentStrategy();
            default:
                throw new IllegalArgumentException("PaymentMethod not found");
        }
    }
}

public class BookMyShow2 {
    private static volatile BookMyShow2 instance;
    private final Map<String, Movie> movieMap;
    private final Map<String, Theatre> theatreMap;
    private final Map<String, Map<String, List<Show>>> showMap;
    private final Map<String, Booking> bookingMap;
    private final BookingService bookingService;
    private volatile ChargingStrategy chargingStrategy;

    private BookMyShow2() {
        movieMap = new ConcurrentHashMap<>();
        theatreMap = new ConcurrentHashMap<>();
        showMap = new ConcurrentHashMap<>();
        bookingService = new BookingService();
        bookingMap = new ConcurrentHashMap<>();
        chargingStrategy = new FixedRateChargingStrategy();
    }

    public static BookMyShow2 getInstance() {
        if (instance == null)
            synchronized (BookMyShow2.class) {
                if (instance == null)
                    instance = new BookMyShow2();
            }
        return instance;
    }

    public void setChargingStrategy(ChargingStrategy chargingStrategy) {
        this.chargingStrategy = chargingStrategy;
    }

    public void addMovie(Movie movie) {
        movieMap.putIfAbsent(movie.getId(), movie);
        showMap.putIfAbsent(movie.getId(), new ConcurrentHashMap<>());
    }

    public void addTheatre(Theatre theatre) {
        theatreMap.putIfAbsent(theatre.getId(), theatre);
    }

    public void addShow(Movie movie, Theatre theatre, Screen screen, LocalDateTime startTime, LocalDateTime endTime) {
        if (!movieMap.containsKey(movie.getId()))
            throw new IllegalArgumentException("Movie not found");
        if (!theatreMap.containsKey(theatre.getId()))
            throw new IllegalArgumentException("Theatre not found");
        showMap.get(movie.getId()).computeIfAbsent(theatre.getId(), k -> new CopyOnWriteArrayList<>())
                .add(new Show(movie, theatre, startTime, endTime, screen));
    }

    public Booking bookTicket(Show show, List<ShowSeat> showSeats, PaymentMethod paymentMethod) {
        Booking booking = bookingService.bookTickets(show, showSeats, paymentMethod);
        bookingMap.put(booking.getId(), booking);
        double charge = chargingStrategy.getCharge(booking);
        booking.setCharge(charge);
        PaymentStrategy paymentStrategy = PaymentFactory.getPaymentStrategy(paymentMethod);
        paymentStrategy.payCharge(booking);
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        return booking;
    }

    public void cancelTicket(Booking booking) {
        if (!bookingMap.containsKey(booking.getId()))
            throw new IllegalArgumentException("Booking not found");
        bookingService.cancelBooking(booking);
    }
}
