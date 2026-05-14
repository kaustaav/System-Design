package TicTacToe;

import java.util.ArrayList;
import java.util.List;

enum Symbol {
    X('X'),
    O('O'),
    EMPTY('_');

    private final char displayChar;

    Symbol(char displayChar) {
        this.displayChar = displayChar;
    }

    public char getDisplayChar() {
        return displayChar;
    }
}

enum GameStatus {
    IN_PROGRESS,
    WINNER_X,
    WINNER_O,
    DRAW
}

class InvalidMoveException extends RuntimeException {
    public InvalidMoveException(String message) {
        super(message);
    }
}

class Player {
    private final String name;
    private final Symbol symbol;

    public Player(String name, Symbol symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    public String getName() {
        return this.name;
    }

    public Symbol getSymbol() {
        return this.symbol;
    }
}

class Cell {
    private Symbol symbol;

    public Cell() {
        this.symbol = Symbol.EMPTY;
    }

    public Symbol getSymbol() {
        return this.symbol;
    }

    public void setSymbol(Symbol symbol) {
        this.symbol = symbol;
    }

    public boolean isEmpty() {
        return symbol == Symbol.EMPTY;
    }
}

class Board {
    private final Cell[][] grid;
    private final int size;

    public Board(int size) {
        this.size = size;
        this.grid = new Cell[size][size];

        initialize();
    }

    public int getSize() {
        return size;
    }

    private void initialize() {
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++)
                grid[i][j] = new Cell();
    }

    public void putSymbol(int row, int col, Symbol symbol) {
        if (isCellEmpty(row, col))
            grid[row][col].setSymbol(symbol);
    }

    public boolean isCellEmpty(int row, int col) {
        validatePosition(row, col);
        return grid[row][col].isEmpty();
    }

    public Cell getCell(int row, int col) {
        validatePosition(row, col);
        return grid[row][col];
    }

    public boolean isBoardFull() {
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++)
                if (grid[i][j].isEmpty())
                    return false;
        return true;
    }

    public void validatePosition(int row, int col) {
        if (row < 0 || col < 0 || row >= size || col >= size)
            throw new InvalidMoveException("Position (" + row + ", " + col + ") is out of bounds");
    }
}

interface WinningStrategy {
    boolean checkWin(Board board, int row, int col, Symbol symbol);
}

class RowWinningStrategy implements WinningStrategy {
    @Override
    public boolean checkWin(Board board, int row, int col, Symbol symbol) {
        for (int i = 0; i < board.getSize(); i++)
            if (board.getCell(row, i).getSymbol() != symbol)
                return false;
        return true;
    }
}

class ColumnWinningStrategy implements WinningStrategy {
    @Override
    public boolean checkWin(Board board, int row, int col, Symbol symbol) {
        for (int i = 0; i < board.getSize(); i++)
            if (board.getCell(i, col).getSymbol() != symbol)
                return false;
        return true;
    }
}

class DiagonalWinningStrategy implements WinningStrategy {
    @Override
    public boolean checkWin(Board board, int row, int col, Symbol symbol) {
        boolean isWin = true;
        for (int i = 0; i < board.getSize(); i++)
            if (board.getCell(i, i).getSymbol() != symbol)
                isWin = false;
        if (isWin)
            return true;

        for (int i = 0; i < board.getSize(); i++)
            if (board.getCell(i, board.getSize() - i - 1).getSymbol() != symbol)
                return false;
        return true;
    }
}

class Game {
    private final Player[] players;
    private final Board board;
    private GameStatus gameStatus;
    private final List<WinningStrategy> winningStrategies;
    private int currentPlayerIndex;
    private int movesSoFar;

    public Game(Player player1, Player player2, int boardSize) {
        this.board = new Board(boardSize);
        this.players = new Player[] { player1, player2 };
        this.gameStatus = GameStatus.IN_PROGRESS;
        this.winningStrategies = initializeWinningStrategies();
        this.movesSoFar = 0;
    }

    public List<WinningStrategy> initializeWinningStrategies() {
        List<WinningStrategy> winningStrategies = new ArrayList<>();
        winningStrategies.add(new RowWinningStrategy());
        winningStrategies.add(new ColumnWinningStrategy());
        winningStrategies.add(new DiagonalWinningStrategy());
        return winningStrategies;
    }

    public void makeMove(int row, int col) {
        if (gameStatus != GameStatus.IN_PROGRESS)
            throw new InvalidMoveException("Game is already over");
        if (!board.getCell(row, col).isEmpty())
            throw new InvalidMoveException("Cell (" + row + ", " + col + ") is already occupied");

        movesSoFar++;

        Player currentPlayer = getCurrentPlayer();
        Symbol currenPlayerSymbol = currentPlayer.getSymbol();
        board.putSymbol(row, col, currenPlayerSymbol);

        if (checkWin(row, col, currenPlayerSymbol)) {
            System.out.println("Player " + currentPlayer.getName() + " won.");
            gameStatus = (currenPlayerSymbol == Symbol.X)
                    ? GameStatus.WINNER_X
                    : GameStatus.WINNER_O;
            return;
        }

        if (movesSoFar == board.getSize() * board.getSize()) {
            gameStatus = GameStatus.DRAW;
            return;
        }

        currentPlayerIndex = (currentPlayerIndex + 1) % 2;
    }

    private boolean checkWin(int row, int col, Symbol symbol) {
        for (WinningStrategy winningStrategy : winningStrategies) {
            if (winningStrategy.checkWin(board, row, col, symbol))
                return true;
        }
        return false;
    }

    public Board getBoard() {
        return board;
    }

    public Player getCurrentPlayer() {
        return players[currentPlayerIndex];
    }

    public GameStatus getGameStatus() {
        return gameStatus;
    }
}

class Demo {
    public static void main(String[] args) {
        final Player alice = new Player("Alice", Symbol.X);
        final Player bob = new Player("Bob", Symbol.O);
        final int boardSize = 3;

        Game game = new Game(alice, bob, boardSize);

        game.makeMove(0, 0);
        game.makeMove(1, 0);
        game.makeMove(1, 1);
        game.makeMove(1, 2);
        game.makeMove(2, 2);
        game.makeMove(1, 0);
    }
}