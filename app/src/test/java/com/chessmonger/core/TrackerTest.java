package com.chessmonger.core;

import org.junit.Test;
import static org.junit.Assert.*;

import java.awt.image.BufferedImage;

/**
 * Tracker end-to-end, on rendered frames, with no Android.
 * Each position is fed twice (the app's 2-frame baseline /
 * confirmation rule), then the next position, exactly as
 * live frames arrive. The tracker must end each position at
 * the true FEN, without a manual Resync or FEN.
 *
 * Game used: the Stockfish-vs-Stockfish game from the v1.12
 * livetest (~/workspace/livetest/game.json), first 12 moves,
 * plus Fool's Mate for checkmate pause + new-game recovery.
 */
public class TrackerTest {

    private static final String[] MOVES = {
            "e2e4", "c7c5", "g1f3", "b8c6", "f1b5", "g7g6",
            "e1g1", "f8g7", "b5c6", "b7c6", "f1e1", "d8c7"
    };

    private void feed(Tracker t, String fen, TestBoardRenderer.Theme theme, String lastMove) {
        BufferedImage im = TestBoardRenderer.render(fen, theme, lastMove);
        int[] px = TestBoardRenderer.pixels(im);
        t.processPixels(px, 945, 2048);
        t.processPixels(px, 945, 2048);
    }

    @Test public void tracksOpeningMovesOnWood() {
        TestBoardRenderer.Theme theme = TestBoardRenderer.THEMES.get("chesscom_wood");
        Tracker t = new Tracker(1);
        t.start(ChessBoard.START_FEN);
        // Start position, twice, to lock + confirm baseline.
        feed(t, ChessBoard.START_FEN, theme, null);
        assertEquals(ChessBoard.START_FEN, t.fen());
        // Play the game: each new position fed twice, with its
        // last-move highlight. Tracker must confirm each move
        // (highlight fast path: 2 frames) and end at the true FEN.
        ChessBoard truth = new ChessBoard();
        for (String move : MOVES) {
            truth.applyUci(move);
            // Feed the new position twice; the tracker needs
            // 2 identical frames to confirm a highlighted move.
            // Run a few extra identical frames to be safe
            // (the tracker also needs baseline confirmation on
            // the first position, already done above).
            for (int i = 0; i < 3; i++) {
                BufferedImage im = TestBoardRenderer.render(truth.toFen(), theme, move);
                t.processPixels(TestBoardRenderer.pixels(im), 945, 2048);
            }
            assertEquals("after " + move, truth.toFen(), t.fen());
        }
    }

    @Test public void checkmatePausesAndNewGameResumes() {
        TestBoardRenderer.Theme theme = TestBoardRenderer.THEMES.get("lichess_brown");
        Tracker t = new Tracker(1);
        t.start(ChessBoard.START_FEN);
        ChessBoard truth = new ChessBoard();
        String[] mateMoves = {"f2f3", "e7e5", "g2g4", "d8h4"};
        feed(t, ChessBoard.START_FEN, theme, null);
        for (String move : mateMoves) {
            truth.applyUci(move);
            for (int i = 0; i < 3; i++) {
                BufferedImage im = TestBoardRenderer.render(truth.toFen(), theme, move);
                t.processPixels(TestBoardRenderer.pixels(im), 945, 2048);
            }
            assertEquals("after " + move, truth.toFen(), t.fen());
        }
        // One more mated frame should enter the pause.
        BufferedImage mateIm = TestBoardRenderer.render(truth.toFen(), theme, "d8h4");
        t.processPixels(TestBoardRenderer.pixels(mateIm), 945, 2048);
        assertTrue("tracker should be paused at checkmate", t.gameOverPaused());
        // Post-game review moves must NOT be tracked while paused.
        BufferedImage review = TestBoardRenderer.render(
                "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR w KQkq - 0 2", theme, null);
        for (int i = 0; i < 3; i++) {
            t.processPixels(TestBoardRenderer.pixels(review), 945, 2048);
        }
        assertEquals(truth.toFen(), t.fen());
        // Two start frames resume a new game.
        feed(t, ChessBoard.START_FEN, theme, null);
        assertFalse("pause should clear on new game", t.gameOverPaused());
        assertEquals(ChessBoard.START_FEN, t.fen());
    }

    @Test public void missedMoveRecoversByForwardSearch() {
        TestBoardRenderer.Theme theme = TestBoardRenderer.THEMES.get("chesscom_green");
        Tracker t = new Tracker(1);
        t.start(ChessBoard.START_FEN);
        feed(t, ChessBoard.START_FEN, theme, null);
        // Skip e4 in the feed: jump straight to after 1.e4 e5.
        // The tracker sees a 2-ply jump; recovery (forward
        // search, depth 2, on a stable frame) must find it
        // without a FEN, once the baseline is re-confirmed.
        String after = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2";
        for (int i = 0; i < 4; i++) {
            BufferedImage im = TestBoardRenderer.render(after, theme, "e7e5");
            t.processPixels(TestBoardRenderer.pixels(im), 945, 2048);
        }
        assertEquals(after, t.fen());
    }
}
