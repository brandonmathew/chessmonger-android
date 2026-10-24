package com.chessmonger.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, dependency-free chess rules in pure Java.
 *
 * Square 0 = a1, 1 = b1, ... 63 = h8 (square = rank * 8 + file,
 * rank 0 is White's back rank). Piece codes are signed: positive =
 * White, negative = Black, magnitude 1 pawn, 2 knight, 3 bishop,
 * 4 rook, 5 queen, 6 king.
 *
 * This exists so the tracker can (a) tell which occupancy changes
 * are actually legal moves, and (b) build the FEN Stockfish needs,
 * without pulling in a chess library. It is not an engine: move
 * choice always comes from Stockfish.
 */
public final class ChessBoard {

    public static final String START_FEN =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    public static final int EMPTY = 0;
    public static final int WP = 1, WN = 2, WB = 3, WR = 4, WQ = 5, WK = 6;

    public static final class Move {
        public final int from, to, promotion; // promotion: 0 or 2..5

        public Move(int from, int to, int promotion) {
            this.from = from;
            this.to = to;
            this.promotion = promotion;
        }

        public String uci() {
            String s = sqName(from) + sqName(to);
            if (promotion == 2) return s + "n";
            if (promotion == 3) return s + "b";
            if (promotion == 4) return s + "r";
            if (promotion == 5) return s + "q";
            return s;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Move)) return false;
            Move m = (Move) o;
            return from == m.from && to == m.to && promotion == m.promotion;
        }

        @Override public int hashCode() {
            return from * 100000 + to * 10 + promotion;
        }

        @Override public String toString() { return uci(); }
    }

    public static String sqName(int sq) {
        return "" + (char) ('a' + (sq % 8)) + (char) ('1' + (sq / 8));
    }

    public static int sqIndex(String name) {
        if (name == null || name.length() < 2) return -1;
        int f = name.charAt(0) - 'a';
        int r = name.charAt(1) - '1';
        if (f < 0 || f > 7 || r < 0 || r > 7) return -1;
        return r * 8 + f;
    }

    public static Move moveFromUci(String uci) {
        if (uci == null || uci.length() < 4) return null;
        int from = sqIndex(uci.substring(0, 2));
        int to = sqIndex(uci.substring(2, 4));
        if (from < 0 || to < 0) return null;
        int promo = 0;
        if (uci.length() >= 5) {
            switch (uci.charAt(4)) {
                case 'n': promo = 2; break;
                case 'b': promo = 3; break;
                case 'r': promo = 4; break;
                default: promo = 5; break;
            }
        }
        return new Move(from, to, promo);
    }

    public int[] board = new int[64];
    public int sideToMove = 1; // 1 white, -1 black
    public boolean wk = true, wq = true, bk = true, bq = true;
    public int enPassant = -1;
    // v1.5.0: halfmove/fullmove were hardcoded as "0 1" in toFen,
    // so Stockfish never saw the fifty-move clock or the real move
    // number in a long game. Tracked properly now.
    public int halfmove = 0;
    public int fullmove = 1;

    public ChessBoard() {
        setFen(START_FEN);
    }

    public ChessBoard(String fen) {
        setFen(fen);
    }

    public ChessBoard copy() {
        ChessBoard c = new ChessBoard();
        c.board = board.clone();
        c.sideToMove = sideToMove;
        c.wk = wk; c.wq = wq; c.bk = bk; c.bq = bq;
        c.enPassant = enPassant;
        c.halfmove = halfmove;
        c.fullmove = fullmove;
        return c;
    }

    public boolean setFen(String fen) {
        try {
            String[] parts = fen.trim().split("\\s+");
            int[] b = new int[64];
            String[] rows = parts[0].split("/");
            if (rows.length != 8) return false;
            for (int i = 0; i < 8; i++) {
                int rank = 7 - i, file = 0;
                for (int j = 0; j < rows[i].length(); j++) {
                    char ch = rows[i].charAt(j);
                    if (ch >= '1' && ch <= '8') {
                        file += ch - '0';
                    } else {
                        int p = 0;
                        switch (Character.toLowerCase(ch)) {
                            case 'p': p = 1; break;
                            case 'n': p = 2; break;
                            case 'b': p = 3; break;
                            case 'r': p = 4; break;
                            case 'q': p = 5; break;
                            case 'k': p = 6; break;
                            default: return false; // not a piece letter
                        }
                        if (file >= 8) return false; // row overflows
                        if (Character.isLowerCase(ch)) p = -p;
                        b[rank * 8 + file] = p;
                        file++;
                    }
                }
                // v1.5.0: a row must total exactly 8 squares. The
                // old code accepted short/long rows and then built
                // a board with missing/duplicated squares, which
                // corrupted tracking silently instead of rejecting
                // the FEN.
                if (file != 8) return false;
            }
            // A legal position has exactly one king per side. A FEN
            // without a king made kingSquare() return -1, inCheck()
            // return false, and legalMoves() generate moves with no
            // king safety at all — reject it instead.
            int wkCount = 0, bkCount = 0;
            for (int p : b) {
                if (p == 6) wkCount++;
                if (p == -6) bkCount++;
            }
            if (wkCount != 1 || bkCount != 1) return false;
            board = b;
            sideToMove = parts.length > 1 && parts[1].equals("b") ? -1 : 1;
            String cast = parts.length > 2 ? parts[2] : "-";
            wk = cast.contains("K"); wq = cast.contains("Q");
            bk = cast.contains("k"); bq = cast.contains("q");
            enPassant = parts.length > 3 && !parts[3].equals("-")
                    ? sqIndex(parts[3]) : -1;
            halfmove = 0;
            fullmove = 1;
            if (parts.length > 4) {
                try { halfmove = Integer.parseInt(parts[4]); }
                catch (Exception ignored) { halfmove = 0; }
            }
            if (parts.length > 5) {
                try { fullmove = Integer.parseInt(parts[5]); }
                catch (Exception ignored) { fullmove = 1; }
            }
            if (halfmove < 0) halfmove = 0;
            if (fullmove < 1) fullmove = 1;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String toFen() {
        StringBuilder sb = new StringBuilder();
        for (int rank = 7; rank >= 0; rank--) {
            int empty = 0;
            for (int file = 0; file < 8; file++) {
                int p = board[rank * 8 + file];
                if (p == 0) { empty++; continue; }
                if (empty > 0) { sb.append(empty); empty = 0; }
                char ch = "pnbrqk".charAt(Math.abs(p) - 1);
                sb.append(p > 0 ? Character.toUpperCase(ch) : ch);
            }
            if (empty > 0) sb.append(empty);
            if (rank > 0) sb.append('/');
        }
        sb.append(sideToMove == 1 ? " w " : " b ");
        String cast = (wk ? "K" : "") + (wq ? "Q" : "")
                + (bk ? "k" : "") + (bq ? "q" : "");
        sb.append(cast.isEmpty() ? "-" : cast).append(' ');
        sb.append(enPassant < 0 ? "-" : sqName(enPassant));
        sb.append(' ').append(halfmove).append(' ').append(fullmove);
        return sb.toString();
    }

    public int pieceCount() {
        int n = 0;
        for (int p : board) if (p != 0) n++;
        return n;
    }

    public boolean isStartingPosition() {
        return toFen().startsWith(
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w");
    }

    private int kingSquare(int side) {
        int want = side * 6;
        for (int i = 0; i < 64; i++) if (board[i] == want) return i;
        return -1;
    }

    public boolean inCheck(int side) {
        int k = kingSquare(side);
        return k >= 0 && isAttacked(k, -side);
    }

    /** Is sq attacked by the given side? side: 1 white, -1 black. */
    public boolean isAttacked(int sq, int bySide) {
        int rank = sq / 8, file = sq % 8;
        // Pawns: a white pawn attacks from one rank below, black from above.
        int pr = rank - bySide;
        for (int df = -1; df <= 1; df += 2) {
            int f = file + df;
            if (pr >= 0 && pr < 8 && f >= 0 && f < 8
                    && board[pr * 8 + f] == bySide * 1) return true;
        }
        int[][] knight = {{1,2},{2,1},{2,-1},{1,-2},{-1,-2},{-2,-1},{-2,1},{-1,2}};
        for (int[] d : knight) {
            int r = rank + d[0], f = file + d[1];
            if (r >= 0 && r < 8 && f >= 0 && f < 8
                    && board[r * 8 + f] == bySide * 2) return true;
        }
        int[][] king = {{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1},{0,-1},{1,-1}};
        for (int[] d : king) {
            int r = rank + d[0], f = file + d[1];
            if (r >= 0 && r < 8 && f >= 0 && f < 8
                    && board[r * 8 + f] == bySide * 6) return true;
        }
        int[][] diag = {{1,1},{1,-1},{-1,1},{-1,-1}};
        for (int[] d : diag) {
            for (int step = 1; step < 8; step++) {
                int r = rank + d[0] * step, f = file + d[1] * step;
                if (r < 0 || r >= 8 || f < 0 || f >= 8) break;
                int p = board[r * 8 + f];
                if (p == 0) continue;
                if (p == bySide * 3 || p == bySide * 5) return true;
                break;
            }
        }
        int[][] ortho = {{1,0},{-1,0},{0,1},{0,-1}};
        for (int[] d : ortho) {
            for (int step = 1; step < 8; step++) {
                int r = rank + d[0] * step, f = file + d[1] * step;
                if (r < 0 || r >= 8 || f < 0 || f >= 8) break;
                int p = board[r * 8 + f];
                if (p == 0) continue;
                if (p == bySide * 4 || p == bySide * 5) return true;
                break;
            }
        }
        return false;
    }

    private void addMove(List<Move> out, int from, int to) {
        out.add(new Move(from, to, 0));
    }

    private void addPawnMove(List<Move> out, int from, int to, int side) {
        int rank = to / 8;
        if ((side == 1 && rank == 7) || (side == -1 && rank == 0)) {
            out.add(new Move(from, to, 5));
            out.add(new Move(from, to, 4));
            out.add(new Move(from, to, 3));
            out.add(new Move(from, to, 2));
        } else {
            out.add(new Move(from, to, 0));
        }
    }

    private List<Move> pseudoMoves() {
        List<Move> out = new ArrayList<>();
        int side = sideToMove;
        for (int sq = 0; sq < 64; sq++) {
            int p = board[sq];
            if (p == 0 || (p > 0) != (side > 0)) continue;
            int type = Math.abs(p), rank = sq / 8, file = sq % 8;
            if (type == 1) {
                int dir = side; // white moves to higher ranks
                int one = sq + dir * 8;
                if (one >= 0 && one < 64 && board[one] == 0) {
                    addPawnMove(out, sq, one, side);
                    int start = side == 1 ? 1 : 6;
                    int two = sq + dir * 16;
                    if (rank == start && board[two] == 0) {
                        out.add(new Move(sq, two, 0));
                    }
                }
                for (int df = -1; df <= 1; df += 2) {
                    int f = file + df, r = rank + dir;
                    if (f < 0 || f > 7 || r < 0 || r > 7) continue;
                    int t = r * 8 + f;
                    if (board[t] != 0 && (board[t] > 0) != (side > 0)) {
                        addPawnMove(out, sq, t, side);
                    } else if (t == enPassant) {
                        out.add(new Move(sq, t, 0));
                    }
                }
            } else if (type == 2) {
                int[][] d = {{1,2},{2,1},{2,-1},{1,-2},{-1,-2},{-2,-1},{-2,1},{-1,2}};
                for (int[] o : d) {
                    int r = rank + o[0], f = file + o[1];
                    if (r < 0 || r >= 8 || f < 0 || f >= 8) continue;
                    int t = r * 8 + f;
                    if (board[t] == 0 || (board[t] > 0) != (side > 0)) {
                        addMove(out, sq, t);
                    }
                }
            } else if (type == 3 || type == 4 || type == 5) {
                int[][] dirs;
                if (type == 3) dirs = new int[][]{{1,1},{1,-1},{-1,1},{-1,-1}};
                else if (type == 4) dirs = new int[][]{{1,0},{-1,0},{0,1},{0,-1}};
                else dirs = new int[][]{{1,1},{1,-1},{-1,1},{-1,-1},
                        {1,0},{-1,0},{0,1},{0,-1}};
                for (int[] d : dirs) {
                    for (int step = 1; step < 8; step++) {
                        int r = rank + d[0] * step, f = file + d[1] * step;
                        if (r < 0 || r >= 8 || f < 0 || f >= 8) break;
                        int t = r * 8 + f;
                        if (board[t] == 0) { addMove(out, sq, t); continue; }
                        if ((board[t] > 0) != (side > 0)) addMove(out, sq, t);
                        break;
                    }
                }
            } else if (type == 6) {
                for (int dr = -1; dr <= 1; dr++) {
                    for (int df = -1; df <= 1; df++) {
                        if (dr == 0 && df == 0) continue;
                        int r = rank + dr, f = file + df;
                        if (r < 0 || r >= 8 || f < 0 || f >= 8) continue;
                        int t = r * 8 + f;
                        if (board[t] == 0 || (board[t] > 0) != (side > 0)) {
                            addMove(out, sq, t);
                        }
                    }
                }
                // Castling: king on e-file, rights, empty path, path not attacked.
                if (side == 1 && sq == 4) {
                    if (wk && board[5] == 0 && board[6] == 0 && board[7] == WR
                            && !isAttacked(4, -1) && !isAttacked(5, -1)
                            && !isAttacked(6, -1)) addMove(out, 4, 6);
                    if (wq && board[3] == 0 && board[2] == 0 && board[1] == 0
                            && board[0] == WR && !isAttacked(4, -1)
                            && !isAttacked(3, -1) && !isAttacked(2, -1)) {
                        addMove(out, 4, 2);
                    }
                }
                if (side == -1 && sq == 60) {
                    if (bk && board[61] == 0 && board[62] == 0 && board[63] == -WR
                            && !isAttacked(60, 1) && !isAttacked(61, 1)
                            && !isAttacked(62, 1)) addMove(out, 60, 62);
                    if (bq && board[59] == 0 && board[58] == 0 && board[57] == 0
                            && board[56] == -WR && !isAttacked(60, 1)
                            && !isAttacked(59, 1) && !isAttacked(58, 1)) {
                        addMove(out, 60, 58);
                    }
                }
            }
        }
        return out;
    }

    public List<Move> legalMoves() {
        List<Move> out = new ArrayList<>();
        int side = sideToMove;
        for (Move m : pseudoMoves()) {
            ChessBoard next = copy();
            next.applyUnchecked(m);
            if (!next.inCheck(side)) out.add(m);
        }
        return out;
    }

    /** Apply without legality checking (tracker/engine already validated). */
    public void applyUnchecked(Move m) {
        int p = board[m.from];
        int captured = board[m.to];
        boolean isEnPassantCapture =
                Math.abs(p) == 1 && m.to == enPassant && captured == 0;
        board[m.from] = 0;
        // En passant capture: captured pawn sits behind the target square.
        if (Math.abs(p) == 1 && m.to == enPassant && captured == 0) {
            board[m.to - sideToMove * 8] = 0;
        }
        if (m.promotion != 0) {
            board[m.to] = sideToMove * m.promotion;
        } else {
            board[m.to] = p;
        }
        // Castling rook.
        if (Math.abs(p) == 6 && Math.abs(m.to - m.from) == 2) {
            if (m.to == 6) { board[5] = board[7]; board[7] = 0; }
            if (m.to == 2) { board[3] = board[0]; board[0] = 0; }
            if (m.to == 62) { board[61] = board[63]; board[63] = 0; }
            if (m.to == 58) { board[59] = board[56]; board[56] = 0; }
        }
        // Castling rights.
        if (m.from == 4 || m.to == 4) { wk = false; wq = false; }
        if (m.from == 0 || m.to == 0) wq = false;
        if (m.from == 7 || m.to == 7) wk = false;
        if (m.from == 60 || m.to == 60) { bk = false; bq = false; }
        if (m.from == 56 || m.to == 56) bq = false;
        if (m.from == 63 || m.to == 63) bk = false;
        // New en passant square only on a double pawn push.
        if (Math.abs(p) == 1 && Math.abs(m.to - m.from) == 16) {
            enPassant = (m.from + m.to) / 2;
        } else {
            enPassant = -1;
        }
        // Fifty-move clock: a pawn move or any capture resets it.
        if (Math.abs(p) == 1 || captured != 0 || isEnPassantCapture) {
            halfmove = 0;
        } else {
            halfmove++;
        }
        // Fullmove number rises after Black's move.
        if (sideToMove == -1) fullmove++;
        sideToMove = -sideToMove;
    }

    public boolean applyUci(String uci) {
        Move m = moveFromUci(uci);
        if (m == null) return false;
        for (Move legal : legalMoves()) {
            if (legal.equals(m)) { applyUnchecked(legal); return true; }
        }
        // Stockfish always returns a legal move for a legal FEN; if our
        // generator disagrees (e.g. a FEN edge case), trust the engine.
        applyUnchecked(m);
        return true;
    }

    /**
     * Only the clear insufficient-material cases: bare kings, a
     * single minor against a bare king, and bishop-vs-bishop on
     * same-colour squares. Deliberately conservative — KNN vs K
     * and other edge cases are NOT declared draws here, because
     * a false draw would stop suggestions in a playable game.
     */
    public boolean isInsufficientMaterial() {
        int minorCount = 0;
        int bishopCount = 0;
        int firstBishopSide = 0;
        int firstBishopColour = -1;
        boolean bishopsOppositeSameColour = false;
        for (int sq = 0; sq < 64; sq++) {
            int p = board[sq];
            int t = Math.abs(p);
            if (t == 0 || t == 6) continue;
            if (t != 2 && t != 3) {
                return false; // pawn/rook/queen can always mate
            }
            minorCount++;
            if (t == 3) {
                bishopCount++;
                int colour = (sq / 8 + sq % 8) % 2;
                if (bishopCount == 1) {
                    firstBishopSide = p > 0 ? 1 : -1;
                    firstBishopColour = colour;
                } else if (colour == firstBishopColour
                        && (p > 0 ? 1 : -1) != firstBishopSide) {
                    bishopsOppositeSameColour = true;
                }
            }
        }
        if (minorCount == 0) return true;
        if (minorCount == 1) return true;
        if (minorCount == 2 && bishopCount == 2
                && bishopsOppositeSameColour) {
            return true;
        }
        return false;
    }

    /**
     * Automatic recovery search, v1.5.0: find a short legal move
     * sequence from this position whose resulting occupancy
     * matches a target occupancy (white/black/empty per square,
     * as BoardDetector produces). Used when the tracker is out
     * of sync and no FEN is available: a missed move, or a pair
     * of missed moves (e.g. the board was hidden while both
     * sides moved), can be recovered exactly this way, because
     * the sequence determines the piece types, not just colours.
     *
     * Acceptance is strict, on purpose: a sequence is returned
     * only if it is the UNIQUE best match, with resulting delta
     * at most 2 (allowing 1-2 misclassified squares) and at
     * least 3 better than the next-best sequence. If two
     * different sequences could explain the same colours — for
     * example two knights swapping squares, or a bishop vs a
     * queen landing on the same square — the search returns null
     * rather than guessing a piece type and desyncing again on a
     * false recovery. Depth is capped at 2: deeper searches grow
     * exponentially and, in practice, a tracker that is running
     * continuously never misses more than a pair of moves before
     * declaring desync; anything deeper needs a pasted FEN.
     */
    public static List<Move> findSequenceToOccupancy(ChessBoard start,
                                                     int[] target,
                                                     int maxDepth) {
        if (target == null || target.length != 64) return null;
        List<Move> best = null;
        int bestDelta = Integer.MAX_VALUE;
        int secondDelta = Integer.MAX_VALUE;
        boolean ambiguous = false;
        // Depth 1.
        for (Move m1 : start.legalMoves()) {
            ChessBoard b1 = start.copy();
            b1.applyUnchecked(m1);
            int d1 = occupancyDeltaFor(b1, target);
            if (d1 <= 2) {
                if (d1 < bestDelta) {
                    secondDelta = bestDelta;
                    bestDelta = d1;
                    best = new ArrayList<>();
                    best.add(m1);
                    ambiguous = false;
                } else if (d1 == bestDelta) {
                    ambiguous = true;
                } else if (d1 < secondDelta) {
                    secondDelta = d1;
                }
            }
            if (maxDepth < 2) continue;
            for (Move m2 : b1.legalMoves()) {
                ChessBoard b2 = b1.copy();
                b2.applyUnchecked(m2);
                int d2 = occupancyDeltaFor(b2, target);
                if (d2 <= 2) {
                    if (d2 < bestDelta) {
                        secondDelta = bestDelta;
                        bestDelta = d2;
                        best = new ArrayList<>();
                        best.add(m1);
                        best.add(m2);
                        ambiguous = false;
                    } else if (d2 == bestDelta) {
                        ambiguous = true;
                    } else if (d2 < secondDelta) {
                        secondDelta = d2;
                    }
                }
            }
        }
        if (best == null || ambiguous) return null;
        // An exact, unique match wins outright: a pawn move one
        // square off (e3 instead of e4) also scores delta 2, so
        // requiring a gap of 3 would reject exact matches for
        // almost every pawn move. Only the tolerant case
        // (delta 1-2, allowing misclassified squares) needs a
        // clear gap to the next-best sequence.
        if (bestDelta == 0) return best;
        if (secondDelta - bestDelta < 3) return null;
        return best;
    }

    private static int occupancyDeltaFor(ChessBoard b, int[] target) {
        int[] expected = new int[64];
        for (int i = 0; i < 64; i++) {
            expected[i] = b.board[i] == 0 ? 0
                    : (b.board[i] > 0 ? 1 : 2);
        }
        int n = 0;
        for (int i = 0; i < 64; i++) if (expected[i] != target[i]) n++;
        return n;
    }

    public boolean isGameOver() {
        return legalMoves().isEmpty() || isInsufficientMaterial();
    }

    public String gameOverText() {
        if (legalMoves().isEmpty()) {
            if (inCheck(sideToMove)) {
                return (sideToMove == 1 ? "Black" : "White")
                        + " wins by checkmate";
            }
            return "Draw by stalemate";
        }
        if (isInsufficientMaterial()) {
            return "Draw by insufficient material";
        }
        return null;
    }
}
