package com.chessmonger.core;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Chess rules, in the repo (not /tmp) for the first time.
 * These are the positions that previously lived only in
 * ad-hoc JVM harnesses, and that every tracker change must
 * keep passing: opening, Fool's Mate, castling, en passant,
 * promotion, FEN round-trip, insufficient material, and the
 * forward-search used by recovery (Bd7, Kxh8).
 */
public class ChessBoardTest {

    @Test public void startFenRoundTrip() {
        ChessBoard b = new ChessBoard();
        assertEquals(ChessBoard.START_FEN, b.toFen());
        assertTrue(b.isStartingPosition());
        assertEquals(20, b.legalMoves().size());
    }

    @Test public void openingFen() {
        ChessBoard b = new ChessBoard();
        b.applyUci("e2e4");
        b.applyUci("e7e5");
        b.applyUci("g1f3");
        assertEquals("rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq - 1 2",
                b.toFen());
    }

    @Test public void foolsMateIsCheckmateNotStalemate() {
        ChessBoard b = new ChessBoard();
        b.applyUci("f2f3");
        b.applyUci("e7e5");
        b.applyUci("g2g4");
        b.applyUci("d8h4");
        assertTrue(b.legalMoves().isEmpty());
        assertTrue(b.inCheck(b.sideToMove));
        assertTrue(b.isGameOver());
        assertTrue(b.gameOverText().toLowerCase().contains("checkmate")
                || b.gameOverText().toLowerCase().contains("mate"));
    }

    @Test public void castlingBothSides() {
        ChessBoard b = new ChessBoard("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R3K2R w KQkq - 0 1");
        assertNotNull(b.moveFromUci("e1g1"));
        assertNotNull(b.moveFromUci("e1c1"));
        b.applyUci("e1g1");
        assertEquals("r3k2r/pppppppp/8/8/8/8/PPPPPPPP/R4RK1 b kq - 1 1", b.toFen());
    }

    @Test public void enPassant() {
        ChessBoard b = new ChessBoard();
        b.applyUci("e2e4");
        b.applyUci("a7a6");
        b.applyUci("e4e5");
        b.applyUci("d7d5");
        assertNotNull(b.moveFromUci("e5d6"));
        b.applyUci("e5d6");
        assertEquals("rnbqkbnr/1pp1pppp/p2P4/8/8/8/PPPP1PPP/RNBQKBNR b KQkq - 0 3",
                b.toFen());
    }

    @Test public void promotion() {
        ChessBoard b = new ChessBoard("8/P7/8/8/8/8/8/4K2k w - - 0 1");
        b.applyUci("a7a8q");
        assertTrue(b.toFen().startsWith("Q7/8/8/8/8/8/8/4K2k b"));
    }

    @Test public void insufficientMaterial() {
        ChessBoard b = new ChessBoard("8/8/8/8/8/8/8/4K2k w - - 0 1");
        assertTrue(b.isGameOver());
    }

    @Test public void invalidFenRejected() {
        ChessBoard b = new ChessBoard();
        assertFalse(b.setFen("not a fen"));
        assertFalse(b.setFen("8/8/8/8/8/8/8/8 w - - 0 1")); // no kings
        assertEquals(ChessBoard.START_FEN, b.toFen());
    }

    @Test public void forwardSearchFindsTwoMoves() {
        // Forward search is what recovery uses for missed moves.
        // From the start, the occupancy after 1.e4 e5 must be
        // found as exactly those two moves, depth 2.
        ChessBoard before = new ChessBoard();
        ChessBoard after = new ChessBoard();
        after.applyUci("e2e4");
        after.applyUci("e7e5");
        int[] target = DetectorCore.expectedOccupancy(after);
        java.util.List<ChessBoard.Move> seq =
                ChessBoard.findSequenceToOccupancy(before, target, 2);
        assertNotNull(seq);
        assertEquals(2, seq.size());
        assertEquals("e2e4", seq.get(0).uci());
        assertEquals("e7e5", seq.get(1).uci());
    }

    @Test public void kxh8IsLegalInCheck() {
        // Pre-Kxh8 position from the 2026-10-23 game (15.Rxh8+):
        // black king on g8, in check from the white rook on h8.
        // Only Kg7 and Kxh8 should be among the legal king moves
        // (other pieces are pinned / cannot block the rook).
        ChessBoard b = new ChessBoard(
                "r1b3kR/pn1pqp2/1p1p4/1N6/4P3/4BN2/PPPQ1PP1/R3KB2 b - - 0 15");
        assertTrue(b.inCheck(b.sideToMove));
        boolean foundKxh8 = false, foundKg7 = false;
        for (ChessBoard.Move m : b.legalMoves()) {
            if (m.uci().equals("g8h8")) foundKxh8 = true;
            if (m.uci().equals("g8g7")) foundKg7 = true;
        }
        assertTrue(foundKxh8);
        assertTrue(foundKg7);
    }
}
