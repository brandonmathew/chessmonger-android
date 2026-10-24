package com.chessmonger.android;

import android.graphics.Bitmap;
import android.graphics.Rect;

import com.chessmonger.core.ChessBoard;
import com.chessmonger.core.DetectorCore;

import java.util.ArrayList;
import java.util.List;

/**
 * Thin Android wrapper around core DetectorCore, kept so old
 * callers (and any external code) that used BoardDetector still
 * compile. New code should use DetectorCore directly with
 * pixels, which avoids converting a Bitmap on every call.
 *
 * Conversion is Bitmap -> int[] via getPixels, once per call
 * here. CaptureService v2 does NOT use this wrapper on its
 * hot path: it converts once per frame and calls DetectorCore.
 */
public final class BoardDetector {

    public static final int EMPTY = DetectorCore.EMPTY;
    public static final int WHITE_PIECE = DetectorCore.WHITE_PIECE;
    public static final int BLACK_PIECE = DetectorCore.BLACK_PIECE;

    public String lastMethod = "none";
    public double lastCheckerDist = 0;

    private final DetectorCore core = new DetectorCore();

    public DetectorCore core() { return core; }

    public static final class Frame {
        public final Rect board;
        public final int[] occupancy;
        public final List<Integer> highlights;

        public Frame(Rect board, int[] occupancy, List<Integer> highlights) {
            this.board = board;
            this.occupancy = occupancy;
            this.highlights = highlights;
        }
    }

    private static com.chessmonger.core.Rect toCore(Rect r) {
        return new com.chessmonger.core.Rect(r.left, r.top, r.right, r.bottom);
    }

    private static Rect toAndroid(com.chessmonger.core.Rect r) {
        return new Rect(r.left, r.top, r.right, r.bottom);
    }

    private static Frame toAndroid(DetectorCore.Frame f) {
        if (f == null) return null;
        return new Frame(toAndroid(f.board), f.occupancy,
                new ArrayList<>(f.highlights));
    }

    private static int[] pixels(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        return px;
    }

    public void resetCalibration() { core.resetCalibration(); }
    public boolean isCalibrated() { return core.isCalibrated(); }
    public DetectorCore.Calibration snapshotCalibration() { return core.snapshotCalibration(); }
    public void restoreCalibration(DetectorCore.Calibration c) { core.restoreCalibration(c); }

    public void calibrate(Bitmap bmp, Rect board, ChessBoard known, boolean whiteAtBottom) {
        core.calibrate(pixels(bmp), bmp.getWidth(), bmp.getHeight(),
                toCore(board), known, whiteAtBottom);
    }

    public Frame detect(Bitmap bmp, boolean whiteAtBottom) {
        DetectorCore.Frame f = core.detect(pixels(bmp), bmp.getWidth(), bmp.getHeight(), whiteAtBottom);
        lastMethod = core.lastMethod;
        lastCheckerDist = core.lastCheckerDist;
        return toAndroid(f);
    }

    public double checkerDistance(Bitmap bmp, Rect board) {
        return core.checkerDistance(pixels(bmp), bmp.getWidth(), bmp.getHeight(), toCore(board));
    }

    public Frame readBoard(Bitmap bmp, Rect board, boolean whiteAtBottom) {
        return toAndroid(core.readBoard(pixels(bmp), bmp.getWidth(), bmp.getHeight(),
                toCore(board), whiteAtBottom));
    }

    public static Rect squareRect(Rect board, int sq, boolean whiteAtBottom) {
        com.chessmonger.core.Rect r = DetectorCore.squareRect(toCore(board), sq, whiteAtBottom);
        return toAndroid(r);
    }

    public static ChessBoard.Move inferMove(ChessBoard board, int[] prev, int[] curr,
                                            List<Integer> highlights) {
        return DetectorCore.inferMove(board, prev, curr, highlights);
    }

    public static int[] expectedOccupancy(ChessBoard board) {
        return DetectorCore.expectedOccupancy(board);
    }

    public static int occupancyDelta(int[] a, int[] b) {
        return DetectorCore.occupancyDelta(a, b);
    }
}
