package BookMyShow;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

enum SeatType {
    REGULAR(100.0),
    PREMIUM(200.0);

    private final Double price;

    SeatType(Double price) {
        this.price = price;
    }

    public Double getPrice() {
        return price;
    }
}

enum SeatState {
    AVAILABLE,
    LOCKED,
    BOOKED
}

enum BookingState {
    INITIATED,
    PAYMENT_STARTED,
    CONFIRMED,
    CANCELLED,
    FAILED;
}

enum PaymentMethods {
    CREDIT_CARD
}

enum PaymentState {
    PAYMENT_INITIATED,
    PAYMENT_FAILED,
    PAYMENT_SUCCEEDED
}

class Movie {
    private final String movieId;
    private final String movieName;
    private final LocalDate releaseDate;
    private final String type;

    public Movie(String movieId, String movieName, LocalDate releaseDate, String type) {
        this.movieId = movieId;
        this.movieName = movieName;
        this.releaseDate = releaseDate;
        this.type = type;
    }

    public String getMovieId() {
        return movieId;
    }

    public String getMovieName() {
        return movieName;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public String getType() {
        return type;
    }
}

class Seat {
    private final String seatId;
    private final String seatNo;
    private final SeatType seatType;

    public Seat(String seatId, String seatNo, SeatType seatType) {
        this.seatId = seatId;
        this.seatNo = seatNo;
        this.seatType = seatType;
    }

    public String getSeatId() {
        return seatId;
    }

    public String getSeatNo() {
        return seatNo;
    }

    public SeatType getSeatType() {
        return seatType;
    }

    public Double getSeatPrice() {
        return seatType.getPrice();
    }
}

class Screen {
    public final String screenId;
    public final List<Seat> seats;

    public Screen(String screenId, List<Seat> seats) {
        this.screenId = screenId;
        this.seats = seats;
    }

    public String getScreenId() {
        return screenId;
    }

    public List<Seat> getSeats() {
        return seats;
    }

    public void addSeat(Seat seat) {
        seats.add(seat);
    }
}

class Theatre {
    private final String theatreId;
    private final String theatreName;
    private final List<Screen> screens;

    public Theatre(String theatreId, String theatreName, List<Screen> screens) {
        this.theatreId = theatreId;
        this.theatreName = theatreName;
        this.screens = screens;
    }

    public String getTheatreId() {
        return theatreId;
    }

    public String getTheatreName() {
        return theatreName;
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
    private SeatState seatState;

    public ShowSeat(Seat seat) {
        this.seat = seat;
        this.seatState = SeatState.AVAILABLE;
    }

    public Seat getSeat() {
        return seat;
    }

    public SeatState getSeatState() {
        return seatState;
    }

    public void setSeatState(SeatState seatState) {
        this.seatState = seatState;
    }

    public Double getSeatPrice() {
        return seat.getSeatPrice();
    }
}

class Show {
    private final String showId;
    private final Movie movie;
    private final Screen screen;
    private final LocalDateTime startTime;
    private final Map<String, ShowSeat> showSeatMap;

    public Show(String showId, Movie movie, Screen screen, LocalDateTime startTime) {
        this.showId = showId;
        this.movie = movie;
        this.screen = screen;
        this.startTime = startTime;
        this.showSeatMap = new HashMap<>();

        initializeShowSeats(screen);
    }

    private void initializeShowSeats(Screen screen) {
        for (Seat seat : screen.getSeats())
            showSeatMap.putIfAbsent(seat.getSeatId(), new ShowSeat(seat));
    }

    public String getShowId() {
        return showId;
    }

    public Movie getMovie() {
        return movie;
    }

    public Screen getScreen() {
        return screen;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public Map<String, ShowSeat> getShowSeatMap() {
        return showSeatMap;
    }
}

class Booking {
    private final String bookingId;
    private final Show show;
    private BookingState bookingState;
    private final List<ShowSeat> showSeats;
    private final Double amount;

    public Booking(String bookingId, Show show, List<ShowSeat> showSeats, Double amount) {
        this.bookingId = bookingId;
        this.show = show;
        this.bookingState = BookingState.INITIATED;
        this.showSeats = showSeats;
        this.amount = amount;
    }

    public String getBookingId() {
        return bookingId;
    }

    public Show getShow() {
        return show;
    }

    public BookingState getBookingState() {
        return bookingState;
    }

    public List<ShowSeat> getShowSeats() {
        return showSeats;
    }

    public Double getAmount() {
        return amount;
    }

    public void setBookingState(BookingState bookingState) {
        this.bookingState = bookingState;
    }
}

class Payment {
    private final String paymentId;
    private final String bookindId;
    private final Double amount;
    private PaymentState paymentState;

    public Payment(String paymentId, String bookingId, Double amount) {
        this.paymentId = paymentId;
        this.bookindId = bookingId;
        this.amount = amount;
        this.paymentState = PaymentState.PAYMENT_INITIATED;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getBookingId() {
        return bookindId;
    }

    public Double getAmount() {
        return amount;
    }

    public PaymentState getPaymentState() {
        return paymentState;
    }

    public void setPaymentState(PaymentState paymentState) {
        this.paymentState = paymentState;
    }
}

interface PaymentStrategyInterface {
    public void makePayment(Payment payment);
}

interface PricingStrategyInterface {
    public Double getPricingAmount(Show show, List<ShowSeat> showSeats);
}

class CreditCardPaymentStrategy implements PaymentStrategyInterface {
    @Override
    public void makePayment(Payment payment) {
        if (payment.getPaymentState() == PaymentState.PAYMENT_SUCCEEDED)
            System.out.println("Payment already succeeded");
        try {
            System.out.println("Made payment for booking: " + payment.getBookingId() + " worth RS: "
                    + payment.getAmount() + " via Credit Card");
            payment.setPaymentState(PaymentState.PAYMENT_SUCCEEDED);
        } catch (Exception e) {
            payment.setPaymentState(PaymentState.PAYMENT_FAILED);
            throw new IllegalStateException(e);
        }
    }
}

class WeekdaysPricingStrategy implements PricingStrategyInterface {
    @Override
    public Double getPricingAmount(Show show, List<ShowSeat> showSeats) {
        Double amount = 0.0D;
        for (ShowSeat showSeat : showSeats)
            amount += showSeat.getSeatPrice();
        return amount;
    }
}

class BookingService {
    private final Map<String, ReentrantLock> showLocks = new ConcurrentHashMap<>();
    private final PricingStrategyInterface pricingStrategy;
    private final Map<PaymentMethods, PaymentStrategyInterface> paymentMethodsMap;

    public BookingService(PricingStrategyInterface pricingStrategyInterface) {
        this.pricingStrategy = pricingStrategyInterface;
        this.paymentMethodsMap = new HashMap<>();
        paymentMethodsMap.put(PaymentMethods.CREDIT_CARD, new CreditCardPaymentStrategy());
    }

    public Booking createSeatBooking(Show show, List<ShowSeat> showSeats, PaymentMethods paymentMethods) {
        if (show == null)
            throw new IllegalStateException("ShowId can't be null");
        ReentrantLock lock = showLocks.computeIfAbsent(show.getShowId(), e -> new ReentrantLock());
        lock.lock();
        try {
            for (ShowSeat showSeat : showSeats) {
                if (showSeat == null)
                    throw new IllegalStateException("SeatId can't be null");
                if (showSeat.getSeatState() != SeatState.AVAILABLE)
                    throw new IllegalStateException("Seat no longer available");
            }
            for (ShowSeat showSeat : showSeats) {
                showSeat.setSeatState(SeatState.LOCKED);
            }
            Double amount = pricingStrategy.getPricingAmount(show, showSeats);
            System.out.println("Amount to pay: " + amount);
            Booking booking = new Booking(UUID.randomUUID().toString().substring(0, 8), show, showSeats, amount);
            booking.setBookingState(BookingState.PAYMENT_STARTED);
            System.out.println("Payment has been initiated");
            Payment payment = new Payment(UUID.randomUUID().toString().substring(0, 8), booking.getBookingId(), amount);
            PaymentStrategyInterface paymentStrategy = paymentMethodsMap.get(paymentMethods);
            paymentStrategy.makePayment(payment);
            if (payment.getPaymentState().equals(PaymentState.PAYMENT_SUCCEEDED)) {
                booking.setBookingState(BookingState.CONFIRMED);
                for (ShowSeat showSeat : showSeats)
                    showSeat.setSeatState(SeatState.BOOKED);
            } else {
                booking.setBookingState(BookingState.FAILED);
                releaseSeats(showSeats);
            }

            return booking;
        } finally {
            lock.unlock();
        }
    }

    private void releaseSeats(List<ShowSeat> showSeats) {
        for (ShowSeat showSeat : showSeats)
            showSeat.setSeatState(SeatState.AVAILABLE);
    }
}

public class BookMyShow {
    private static volatile BookMyShow instance;
    private final Map<String, Movie> movieMap;
    private final Map<String, Theatre> theatreMap;
    private final Map<String, Map<String, Show>> showsMap;
    private final BookingService bookingService;

    public BookMyShow() {
        this.movieMap = new HashMap<>();
        this.theatreMap = new HashMap<>();
        this.showsMap = new HashMap<>();
        this.bookingService = new BookingService(new WeekdaysPricingStrategy());
    }

    public static BookMyShow getInstance() {
        if (instance == null) {
            synchronized (BookMyShow.class) {
                if (instance == null)
                    instance = new BookMyShow();
            }
        }
        return instance;
    }

    public void addMovie(Movie movie) {
        movieMap.putIfAbsent(movie.getMovieId(), movie);
        showsMap.putIfAbsent(movie.getMovieId(), new HashMap<>());
    }

    public void removeMovie(String movieId) {
        movieMap.remove(movieId);
    }

    public void addTheatre(Theatre theatre) {
        theatreMap.putIfAbsent(theatre.getTheatreId(), theatre);
    }

    public void addScreen(String theatreId, Screen screen) {
        Theatre theatre = theatreMap.get(theatreId);
        theatre.addScreen(screen);
    }

    public void addShow(String showId, Movie movie, Theatre theatre, Screen screen, LocalDateTime startTime) {
        if (!movieMap.containsKey(movie.getMovieId()))
            throw new IllegalStateException("Movie not found");
        if (!theatreMap.containsKey(theatre.getTheatreId()))
            throw new IllegalStateException("Theatre not found");
        showsMap.get(movie.getMovieId()).putIfAbsent(theatre.getTheatreId(),
                new Show(showId, movie, screen, startTime));
    }

    public void createBooking(Show show, List<ShowSeat> showSeats, PaymentMethods paymentMethods) {
        bookingService.createSeatBooking(show, showSeats, paymentMethods);
    }
}