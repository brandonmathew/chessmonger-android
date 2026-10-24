package com.chessmonger.core;

import org.junit.Test;
import static org.junit.Assert.*;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

/**
 * DetectorCore on rendered boards, in the repo.
 * This is the Java version of the Python livetest harness:
 * the same themes that failed in v1.11 (beige, white-on-white,
 * black-on-black) must read exactly here, after calibration
 * from the start position — and the easy themes must not
 * regress. Board finding is also tested on a full screenshot,
 * not just at a known rect.
 */
public class DetectorCoreTest {

    private static final String START = ChessBoard.START_FEN;

    @Test public void allThemesReadStartAndMidgameExactly() {
        // Mid-game FEN: after 1.e4 e5 2.Nf3 Nc6 3.Bb5 a6
        String mid = "r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 0 4";
        for (String themeName : TestBoardRenderer.THEMES.keySet()) {
            TestBoardRenderer.Theme theme = TestBoardRenderer.THEMES.get(themeName);
            DetectorCore det = new DetectorCore();
            BufferedImage startIm = TestBoardRenderer.render(START, theme, null);
            int[] px = TestBoardRenderer.pixels(startIm);
            det.calibrate(px, 945, 2048, TestBoardRenderer.BOARD,
                    new ChessBoard(START), true);
            for (String fen : new String[]{START, mid}) {
                BufferedImage im = TestBoardRenderer.render(fen, theme, null);
                int[] p = TestBoardRenderer.pixels(im);
                DetectorCore.Frame f = det.readBoard(p, 945, 2048,
                        TestBoardRenderer.BOARD, true);
                assertNotNull(themeName + " frame null", f);
                int[] expected = TestBoardRenderer.occupancyOf(fen);
                assertEquals(themeName + " delta on " + fen,
                        0, DetectorCore.occupancyDelta(f.occupancy, expected));
            }
        }
    }

    @Test public void highlightsAreExactlyTheLastMovePair() {
        TestBoardRenderer.Theme theme = TestBoardRenderer.THEMES.get("chesscom_wood");
        DetectorCore det = new DetectorCore();
        BufferedImage startIm = TestBoardRenderer.render(START, theme, null);
        det.calibrate(TestBoardRenderer.pixels(startIm), 945, 2048,
                TestBoardRenderer.BOARD, new ChessBoard(START), true);
        // After 1.e4: highlight should be e2/e4.
        String afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1";
        BufferedImage im = TestBoardRenderer.render(afterE4, theme, "e2e4");
        DetectorCore.Frame f = det.readBoard(TestBoardRenderer.pixels(im), 945, 2048,
                TestBoardRenderer.BOARD, true);
        Set<Integer> hl = new HashSet<>(f.highlights);
        int e2 = (1 * 8 + 4), e4 = (3 * 8 + 4);
        assertEquals(2, hl.size());
        assertTrue(hl.contains(e2));
        assertTrue(hl.contains(e4));
    }

    @Test public void checkerDistanceSeparatesBoardFromBlank() {
        TestBoardRenderer.Theme theme = TestBoardRenderer.THEMES.get("low_contrast");
        DetectorCore det = new DetectorCore();
        BufferedImage boardIm = TestBoardRenderer.render(START, theme, null);
        double boardDist = det.checkerDistance(
                TestBoardRenderer.pixels(boardIm), 945, 2048, TestBoardRenderer.BOARD);
        assertTrue("low-contrast board dist " + boardDist + " should be >= 15",
                boardDist >= 15);
        BufferedImage blank = new BufferedImage(945, 2048, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = blank.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, 945, 2048);
        g.dispose();
        double blankDist = det.checkerDistance(
                TestBoardRenderer.pixels(blank), 945, 2048, TestBoardRenderer.BOARD);
        assertTrue("blank dist " + blankDist + " should be < 15", blankDist < 15);
    }

    @Test public void findBoardOnFullScreenshot() {
        TestBoardRenderer.Theme theme = TestBoardRenderer.THEMES.get("chesscom_green");
        DetectorCore det = new DetectorCore();
        BufferedImage im = TestBoardRenderer.render(START, theme, null);
        DetectorCore.Frame f = det.detect(TestBoardRenderer.pixels(im), 945, 2048, true);
        assertNotNull("board not found on full screenshot", f);
        // Rect should be within a few pixels of the true rect
        // (downscaled edge refinement is ~4 full-res pixels).
        assertTrue(Math.abs(f.board.left - 0) <= 8);
        assertTrue(Math.abs(f.board.top - 608) <= 8);
        assertTrue(Math.abs(f.board.right - 945) <= 8);
        assertTrue(Math.abs(f.board.bottom - 1553) <= 8);
        int[] expected = TestBoardRenderer.occupancyOf(START);
        assertEquals(0, DetectorCore.occupancyDelta(f.occupancy, expected));
    }
}
