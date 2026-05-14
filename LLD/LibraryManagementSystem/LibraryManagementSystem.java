package LibraryManagementSystem;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

// final int DUE_DATE_DIFF = 14;

class User {
    private final String id;
    private final String name;
    private final String contact;

    public User(String id, String name, String contact) {
        this.id = id;
        this.name = name;
        this.contact = contact;
    }

    // getters
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getContact() {
        return contact;
    }
}

interface PaymentStrategy {
    public void payFees(Double fees);
}

class CreditCardPaymentStrategy implements PaymentStrategy {
    public CreditCardPaymentStrategy() {
    }

    @Override
    public void payFees(Double fees) {
        System.out.println("Paid with credit card");
    }
}

interface Observer {
    public void notifyOnReturn(Book book);
}

class AvailabilityObserver implements Observer {
    @Override
    public void notifyOnReturn(Book book) {
        if (book.getReservations().isEmpty())
            return;
        User nextAvailableUser = book.getReservations().poll();
        BookCopy availableCopy = book.getBookCopies().stream().filter(bookCopy -> bookCopy.getCurrentUser() == null)
                .findFirst().orElseThrow(() -> new IllegalStateException("No available copy found"));
        availableCopy.reserveBook(nextAvailableUser);
    }

}

interface BookState {
    public void reserveBook(BookCopy bookCopy, User user);

    public void checkoutBook(BookCopy bookCopy, User user);

    public void returnBook(BookCopy bookCopy, User user, PaymentStrategy paymentStrategy);
}

class AvailableBookState implements BookState {
    @Override
    public void reserveBook(BookCopy bookCopy, User user) {
        bookCopy.setCurrentUser(user);
        bookCopy.setBookState(new ReservedBookState());
    }

    @Override
    public void checkoutBook(BookCopy bookCopy, User user) {
        Lease lease = new Lease(bookCopy, user);
        LeaseManagementSystem.getInstance().createLease(lease);
        bookCopy.setCurrentUser(user);
        bookCopy.setBookState(new CheckedOutBookState());
    }

    @Override
    public void returnBook(BookCopy bookCopy, User user, PaymentStrategy paymentStrategy) {
        throw new IllegalStateException("Can't return available book");
    }
}

class ReservedBookState implements BookState {
    @Override
    public void reserveBook(BookCopy bookCopy, User user) {
        bookCopy.getBook().addReservation(user);
    }

    @Override
    public void checkoutBook(BookCopy bookCopy, User user) {
        if (!bookCopy.getCurrentUser().getId().equals(user.getId()))
            throw new IllegalStateException("Book is reserved to someone else already");
        Lease lease = new Lease(bookCopy, user);
        LeaseManagementSystem.getInstance().createLease(lease);
        bookCopy.setCurrentUser(user);
        bookCopy.setBookState(new CheckedOutBookState());
    }

    @Override
    public void returnBook(BookCopy bookCopy, User user, PaymentStrategy paymentStrategy) {
        throw new IllegalStateException("Can't return reserved book");
    }
}

class CheckedOutBookState implements BookState {
    @Override
    public void reserveBook(BookCopy bookCopy, User user) {
        bookCopy.getBook().addReservation(user);
    }

    @Override
    public void checkoutBook(BookCopy bookCopy, User user) {
        throw new IllegalStateException("Can't checkout unavailable book");
    }

    @Override
    public void returnBook(BookCopy bookCopy, User user, PaymentStrategy paymentStrategy) {
        if (!bookCopy.getCurrentUser().getId().equals(user.getId()))
            throw new IllegalStateException("Book is checked out by someone else already");
        Lease lease = LeaseManagementSystem.getInstance().getLeaseForBookCopy(bookCopy);
        Double fees = LeaseManagementSystem.getInstance().computeFees(lease);
        paymentStrategy.payFees(fees);
        LeaseManagementSystem.getInstance().releaseLease(bookCopy);
        bookCopy.setBookState(new AvailableBookState());
        bookCopy.setCurrentUser(null);
        bookCopy.getBook().notifyObservers();
    }
}

class Book {
    private final String id;
    private final String name;
    private final List<BookCopy> bookCopies;
    private final Queue<User> reservations;
    private final List<Observer> observers;

    public Book(String id, String name, int quantity) {
        this.id = id;
        this.name = name;
        this.observers = new CopyOnWriteArrayList<>();
        if (quantity <= 0)
            throw new IllegalStateException("Book copies can't be negative");
        bookCopies = new ArrayList<BookCopy>();
        this.reservations = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < quantity; i++)
            bookCopies.add(new BookCopy(this.id + "-c" + (i + 1), this));
    }

    public void notifyObservers() {
        for (Observer observer : observers)
            observer.notifyOnReturn(this);
    }

    public void addObserver(Observer observer) {
        observers.add(observer);
    }

    public void reserveBook(User user) {
        if (user == null)
            throw new IllegalStateException("User doens't exist");
        // TODO: further improve
        reservations.add(user);
    }

    public String getId() {
        return id;
    }

    public List<BookCopy> getBookCopies() {
        return Collections.unmodifiableList(bookCopies);
    }

    public String getName() {
        return name;
    }

    public Queue<User> getReservations() {
        return reservations;
    }

    public void addReservation(User user) {
        reservations.add(user);
    }
}

class BookCopy {
    private final String id;
    private final Book book;
    private volatile BookState bookState;
    private User currentUser;

    public BookCopy(String id, Book book) {
        this.id = id;
        this.book = book;
        this.bookState = new AvailableBookState();
        currentUser = null;
    }

    public String getId() {
        return id;
    }

    public void reserveBook(User user) {
        bookState.reserveBook(this, user);
    }

    public void checkoutBook(User user) {
        bookState.checkoutBook(this, user);
    }

    public void returnBook(User user, PaymentStrategy paymentStrategy) {
        bookState.returnBook(this, user, paymentStrategy);
    }

    public Book getBook() {
        return book;
    }

    public BookState getBookState() {
        return bookState;
    }

    public void setBookState(BookState bookState) {
        this.bookState = bookState;
    }

    public User getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(User currentUser) {
        this.currentUser = currentUser;
    }
}

class Lease {
    private final String id;
    private final BookCopy bookCopy;
    private final User user;
    private final LocalDate leaseDate;
    private final LocalDate dueDate;

    public Lease(BookCopy bookCopy, User user) {
        id = UUID.randomUUID().toString().substring(0, 8);
        this.bookCopy = bookCopy;
        this.user = user;
        leaseDate = LocalDate.now();
        dueDate = leaseDate.plusDays(14);
    }

    public LocalDate getLeaseDate() {
        return leaseDate;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public String getId() {
        return id;
    }

    public BookCopy getBookCopy() {
        return bookCopy;
    }

    public User getUser() {
        return user;
    }
}

interface FeesComputeStrategy {
    public double computeFees(Lease lease);
}

class FixedFeesStrategy implements FeesComputeStrategy {
    private final double rate;

    public FixedFeesStrategy(double rate) {
        this.rate = rate;
    }

    @Override
    public double computeFees(Lease lease) {
        LocalDate currentDate = LocalDate.now();
        if (currentDate.isBefore(lease.getDueDate()) || currentDate.equals(lease.getDueDate()))
            return 0;
        else
            return (currentDate.toEpochDay() - lease.getDueDate().toEpochDay()) * rate;
    }
}

class LeaseManagementSystem {
    private static volatile LeaseManagementSystem instance;
    private final Map<String, Lease> leaseMap;
    private volatile FeesComputeStrategy feesComputeStrategy;

    public LeaseManagementSystem() {
        leaseMap = new ConcurrentHashMap<>();
    }

    public static LeaseManagementSystem getInstance() {
        if (instance == null)
            synchronized (LeaseManagementSystem.class) {
                if (instance == null)
                    instance = new LeaseManagementSystem();
            }
        return instance;
    }

    public void createLease(Lease lease) {
        leaseMap.put(lease.getBookCopy().getId(), lease);
    }

    public void releaseLease(BookCopy bookCopy) {
        leaseMap.remove(bookCopy.getId());
    }

    public Lease getLeaseForBookCopy(BookCopy bookCopy) {
        return leaseMap.get(bookCopy.getId());
    }

    public List<Lease> getAllLeaseForUser(String userId) {
        return Collections.unmodifiableList(
                leaseMap.values().stream().filter(lease -> lease.getUser().getId().equals(userId)).toList());
    }

    public double computeFees(Lease lease) {
        return feesComputeStrategy.computeFees(lease);
    }

    public Map<String, Lease> getLeaseMap() {
        return leaseMap;
    }

    public FeesComputeStrategy getFeesComputeStrategy() {
        return feesComputeStrategy;
    }

    public void setFeesComputeStrategy(FeesComputeStrategy feesComputeStrategy) {
        this.feesComputeStrategy = feesComputeStrategy;
    }
}

public class LibraryManagementSystem {
    private static volatile LibraryManagementSystem instance;
    private final LeaseManagementSystem leaseManagementSystem;
    private final Map<String, Book> bookMap;
    private final Map<String, User> userMap;

    protected LibraryManagementSystem() {
        leaseManagementSystem = LeaseManagementSystem.getInstance();
        leaseManagementSystem.setFeesComputeStrategy(new FixedFeesStrategy(1.5));
        bookMap = new ConcurrentHashMap<>();
        userMap = new ConcurrentHashMap<>();
    }

    public static LibraryManagementSystem getInstance() {
        if (instance == null)
            synchronized (LibraryManagementSystem.class) {
                if (instance == null)
                    instance = new LibraryManagementSystem();
            }
        return instance;
    }

    public void addBook(Book book) {
        bookMap.putIfAbsent(book.getId(), book);
    }

    public void addUser(User user) {
        userMap.putIfAbsent(user.getId(), user);
    }

    public List<BookCopy> getBookById(String id) {
        if (!bookMap.containsKey(id))
            throw new IllegalStateException("Book id is invalid");
        return bookMap.get(id).getBookCopies();
    }

    public void checkoutBookByUser(User user, BookCopy bookCopy) {
        if (!userMap.containsKey(user.getId()))
            throw new IllegalStateException("User doesn't exist");
        if (!bookMap.containsKey(bookCopy.getBook().getId()))
            throw new IllegalStateException("Book not found");
        synchronized (user) {
            if (leaseManagementSystem.getAllLeaseForUser(user.getId()).size() >= 3)
                throw new IllegalStateException("Max checkout size reached");
            bookCopy.checkoutBook(user);
        }
    }

    public void returnBookByUser(User user, BookCopy bookCopy, PaymentStrategy paymentStrategy) {
        if (!userMap.containsKey(user.getId()))
            throw new IllegalStateException("User doesn't exist");
        if (!bookMap.containsKey(bookCopy.getBook().getId()))
            throw new IllegalStateException("Book not found");
        bookCopy.returnBook(user, paymentStrategy);

    }

    public void reserveBookByUser(User user, BookCopy bookCopy) {
        if (!userMap.containsKey(user.getId()))
            throw new IllegalStateException("User doesn't exist");
        if (!bookMap.containsKey(bookCopy.getBook().getId()))
            throw new IllegalStateException("Book not found");
        bookCopy.reserveBook(user);

    }

    public List<Lease> getAllListByUser(User user) {
        if (!userMap.containsKey(user.getId()))
            throw new IllegalStateException("User doesn't exist");
        return leaseManagementSystem.getAllLeaseForUser(user.getId());
    }
}
