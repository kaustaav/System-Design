# Tic-Tac-Toe — Entities

---

## Enums
```
Symbol:     X('X'), O('O'), EMPTY('_')    // each carries a char displayChar
GameStatus: IN_PROGRESS, WINNER_X, WINNER_O, DRAW
```

---

## InvalidMoveException extends RuntimeException
```
InvalidMoveException(String message)
```
Thrown by: Board.validatePosition, Game.makeMove

---

## Player
```
String name
Symbol symbol

getters
```

---

## Cell
```
Symbol symbol           // starts EMPTY

boolean isEmpty()       // symbol == Symbol.EMPTY
getSymbol()
setSymbol(Symbol)
```

---

## Board
```
Cell[][] grid           // size × size, all cells initialized to EMPTY
int size

void putSymbol(int row, int col, Symbol)    // no-op if cell is not empty
boolean isCellEmpty(int row, int col)       // calls validatePosition first
Cell getCell(int row, int col)              // calls validatePosition first
boolean isBoardFull()                       // scans grid for any EMPTY cell
void validatePosition(int row, int col)     // throws InvalidMoveException if out of bounds
int getSize()
```

---

## interface WinningStrategy                         // Strategy pattern
```
boolean checkWin(Board board, int row, int col, Symbol symbol)
```

### RowWinningStrategy
- scans entire row `row` — all cells must match `symbol`

### ColumnWinningStrategy
- scans entire column `col` — all cells must match `symbol`

### DiagonalWinningStrategy
- checks main diagonal (i, i) — all match
- if not, checks anti-diagonal (i, size-1-i) — all match
- Note: checks both diagonals on every move, not just the one containing (row, col)

---

## Game
```
Player[] players                    // exactly 2 players
Board board
GameStatus gameStatus               // starts IN_PROGRESS
List<WinningStrategy> winningStrategies   // [Row, Column, Diagonal] — initialized at construction
int currentPlayerIndex              // starts 0, alternates 0/1
int movesSoFar                      // starts 0

void makeMove(int row, int col)
    // throws InvalidMoveException if gameStatus != IN_PROGRESS
    // throws InvalidMoveException if cell is occupied
    // movesSoFar++
    // board.putSymbol(row, col, currentPlayer.symbol)
    // checkWin → if true: print winner, set WINNER_X or WINNER_O, return
    // if movesSoFar == size*size → DRAW
    // else → advance currentPlayerIndex

private boolean checkWin(int row, int col, Symbol)
    // iterates winningStrategies — returns true on first match

List<WinningStrategy> initializeWinningStrategies()   // [Row, Column, Diagonal]
Board getBoard()
Player getCurrentPlayer()
GameStatus getGameStatus()
```

---

## Demo (main class)
- Creates Alice (X) and Bob (O) on a 3×3 board
- Demonstrates a sequence of moves via `game.makeMove(row, col)`

---

## Key Design Notes
| Concern | Solution |
|---|---|
| Win check efficiency | Strategies receive `(row, col)` — Row/Column check only the affected row/column, not the full board |
| Diagonal edge case | DiagonalWinningStrategy checks both diagonals every time — simple and correct for all board sizes |
| Draw detection | `movesSoFar == size * size` — O(1) count, no board scan needed |
| Out-of-bounds protection | `Board.validatePosition` called on every cell access — fails fast with descriptive message |

---

## Flow Summary
- **Setup:** new Game(player1, player2, boardSize) → new Board(size) → initialize all Cells to EMPTY → wire Row/Column/Diagonal strategies
- **Move:** Game.makeMove → validate game state + cell empty → putSymbol → checkWin (any strategy returns true → WINNER) → draw check (movesSoFar == size²) → advance player
- **Win:** first strategy that finds all matching symbols in its line declares the winner; game stops accepting moves
- **Draw:** all cells filled, no winner — gameStatus = DRAW