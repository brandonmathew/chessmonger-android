package com.chessmonger.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the chessboard in a screenshot and reads it, in pure Java
 * (no OpenCV dependency, so the manual APK pipeline stays simple).
 *
 * Detection, in order:
 *  1. Grid lines: Sobel edges projected onto rows/columns. A real
 *     board gives 9 near-equally-spaced strong lines each way.
 *     That sequence is the board rectangle.
 *  2. Checkerboard (added v1.3.2, after a real Chess.com board was
 *     missed): the grid method fails on boards whose squares are
 *     low-contrast (Chess.com green: light/dark differ by only
 *     ~45 in brightness, while the board-vs-background edge and
 *     the UI are far stronger, so a global 40%-of-max threshold
 *     filters the internal lines out) and on boards touching a
 *     screen edge (the outer line is at x=0 / x=width, never an
 *     interior peak). The checkerboard method instead searches
 *     for two alternating square colours, then snaps the coarse
 *     rect to the exact edges by fitting the internal + outer
 *     edge lines. It does not need strong lines to find the board
 *     in the first place.
 *  3. Highlights (v1.8.0, relative): Lichess / Chess.com tint
 *     the last move's two squares. A square whose background
 *     is far from its parity's median background is tinted —
 *     an absolute colour test fired on every bare light square
 *     of a wooden board, so it was replaced. Those two squares,
 *     matched against legal moves, are the most reliable move
 *     signal (same idea as desktop ChessMonger's highlight
 *     detection).
 *  3. Occupancy fallback: compare each square's centre against its
 *     own corners. A piece changes the centre colour a lot; an
 *     empty square does not. White vs black is by centre
 *     brightness once calibrated on a known position, otherwise
 *     by brightness relative to the square itself.
 *
 * Square index here is the same 0=a1..63=h8 scheme as ChessBoard.
 * The detector maps screen squares to board squares through the
 * orientation flag: whiteAtBottom=true is the usual view.
 */
/**
 * Detector core, v2.0.0: the v1 BoardDetector logic, ported to raw
 * pixels with NO Android imports, so it runs unchanged on the
 * phone AND on the JVM in tests. Android code converts a Bitmap
 * to int[] pixels once per frame and calls this; test code
 * converts a BufferedImage the same way. Nothing in this file
 * may import android.* — that is the whole point of the split.
 *
 * The Android shell keeps a thin BoardDetector wrapper that
 * converts Bitmap -> pixels and core Frame -> Android Frame,
 * for any caller that still uses the old API. New code should
 * use DetectorCore directly.
 */
public final class DetectorCore {

    public static final int EMPTY = 0, WHITE_PIECE = 1, BLACK_PIECE = 2;

    /** Calibrated brightness means, learned from a known position. */
    private double whiteMean = -1, blackMean = -1;

    // v1.12.0 colour-agnostic calibration: RGB means per screen
    // parity, learned from a known position. The old classifier
    // only used luminance, and it checked "centre close to
    // background => empty" BEFORE any calibrated check — so a
    // white piece on a near-white square, or a black piece on
    // a near-black square, always read as empty, and calibration
    // could never land because baseline confirmation needs a
    // small delta first. Per-parity RGB means fix both: a beige
    // white piece is still beige in RGB even when its square
    // is also beige, and the nearest-colour test below picks
    // the piece over the empty square when the piece colour is
    // closer. Parity matters because the same piece can shade
    // slightly differently on a light vs a dark square.
    private double[][] whiteRgb = new double[2][3];
    private double[][] blackRgb = new double[2][3];
    private boolean rgbCalibrated = false;

    /** Snapshot of calibration, for trial-and-error recalibration. */
    public static final class Calibration {
        double whiteMean, blackMean;
        double[][] whiteRgb, blackRgb;
        boolean rgbCalibrated;
    }

    public boolean isCalibrated() {
        return rgbCalibrated;
    }

    public Calibration snapshotCalibration() {
        Calibration c = new Calibration();
        c.whiteMean = whiteMean;
        c.blackMean = blackMean;
        c.rgbCalibrated = rgbCalibrated;
        c.whiteRgb = new double[2][3];
        c.blackRgb = new double[2][3];
        for (int i = 0; i < 2; i++) {
            System.arraycopy(whiteRgb[i], 0, c.whiteRgb[i], 0, 3);
            System.arraycopy(blackRgb[i], 0, c.blackRgb[i], 0, 3);
        }
        return c;
    }

    public void restoreCalibration(Calibration c) {
        if (c == null) return;
        whiteMean = c.whiteMean;
        blackMean = c.blackMean;
        rgbCalibrated = c.rgbCalibrated;
        for (int i = 0; i < 2; i++) {
            System.arraycopy(c.whiteRgb[i], 0, whiteRgb[i], 0, 3);
            System.arraycopy(c.blackRgb[i], 0, blackRgb[i], 0, 3);
        }
    }

    /** v1.5.0: drop a learned calibration, e.g. on a soft resync
     * after a theme change, so the next confirmed position
     * relearns piece colours instead of trusting stale ones. */
    public void resetCalibration() {
        whiteMean = -1;
        blackMean = -1;
        rgbCalibrated = false;
    }

    /** Last detection outcome, for the overlay status on failure. */
    public String lastMethod = "none";
    public double lastCheckerDist = 0;

    public static final class Frame {
        public final Rect board;          // board rect in full-res pixels
        public final int[] occupancy;     // 64 x EMPTY/WHITE_PIECE/BLACK_PIECE
        public final List<Integer> highlights; // board squares tinted last-move

        public Frame(Rect board, int[] occupancy, List<Integer> highlights) {
            this.board = board;
            this.occupancy = occupancy;
            this.highlights = highlights;
        }
    }

    /** Learn white/black piece colours from a known board.
     *  Luminance means (old) plus per-parity RGB means (v1.12.0).
     *  This does NOT depend on what the classifier currently
     *  reads: it samples the centre of every square the known
     *  board says is a piece, so it can bootstrap a hard theme
     *  where the uncalibrated classifier misreads badly — as
     *  long as the known board is actually the board on screen
     *  (start position, a confirmed baseline, or a trusted
     *  tracked position). CaptureService only calls it in those
     *  cases, and with snapshot/restore when trialling it. */
    public void calibrate(int[] px, int w, int h, Rect board, ChessBoard known,
                          boolean whiteAtBottom) {
        double wSum = 0, bSum = 0;
        int wN = 0, bN = 0;
        for (int sq = 0; sq < 64; sq++) {
            int p = known.board[sq];
            if (p == 0) continue;
            double lum = centreLuminance(px, w, h, board, sq, whiteAtBottom);
            if (p > 0) { wSum += lum; wN++; } else { bSum += lum; bN++; }
        }
        if (wN > 0) whiteMean = wSum / wN;
        if (bN > 0) blackMean = bSum / bN;
        // Per-parity RGB means.
        boolean anyWhite = false, anyBlack = false;
        for (int parity = 0; parity < 2; parity++) {
            double[] wRgb = new double[3], bRgb = new double[3];
            int wn = 0, bn = 0;
            for (int sq = 0; sq < 64; sq++) {
                int p = known.board[sq];
                if (p == 0) continue;
                int file = sq % 8, rank = sq / 8;
                int col = whiteAtBottom ? file : 7 - file;
                int row = whiteAtBottom ? 7 - rank : rank;
                if ((row + col) % 2 != parity) continue;
                Rect cell = squareRect(board, sq, whiteAtBottom);
                int[] c = meanColor(px, w, h, inset(cell, 0.42f));
                if (p > 0) {
                    for (int ch = 0; ch < 3; ch++) wRgb[ch] += c[ch];
                    wn++;
                } else {
                    for (int ch = 0; ch < 3; ch++) bRgb[ch] += c[ch];
                    bn++;
                }
            }
            if (wn > 0) {
                for (int ch = 0; ch < 3; ch++) whiteRgb[parity][ch] = wRgb[ch] / wn;
                anyWhite = true;
            }
            if (bn > 0) {
                for (int ch = 0; ch < 3; ch++) blackRgb[parity][ch] = bRgb[ch] / bn;
                anyBlack = true;
            }
        }
        if (anyWhite && anyBlack) rgbCalibrated = true;
    }

    /** Full detection pass on a screenshot. Returns null if no board. */
    public Frame detect(int[] px, int w, int h, boolean whiteAtBottom) {
        Rect board = findBoard(px, w, h);
        if (board != null) {
            lastMethod = "grid";
            return readBoard(px, w, h, board, whiteAtBottom);
        }
        board = findBoardByCheckerboard(px, w, h);
        if (board != null) {
            lastMethod = "checker";
            return readBoard(px, w, h, board, whiteAtBottom);
        }
        lastMethod = "none";
        return null;
    }

    /**
     * Checkerboard distance for an already-locked rect, on the
     * full-res frame (256 corner-sample reads — cheap enough
     * every tick). Used by the lock guard: a board that is still
     * on screen keeps a large distance (~60 on Chess.com green);
     * a home screen, a popup covering the board, or a black
     * capture collapses it. Screen row/col parity, not board
     * squares: the pattern is a property of the picture, not of
     * the position or the orientation.
     */
    public double checkerDistance(int[] px, int w, int h, Rect board) {
        if (board.width() < 16 || board.height() < 16) return 0;
        double[] sum = new double[6];
        int[] count = new int[2];
        double cellW = board.width() / 8.0;
        double cellH = board.height() / 8.0;
        double[] offs = {0.18, 0.82};
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int parity = (row + col) % 2;
                for (double dx : offs) {
                    for (double dy : offs) {
                        int x = (int) Math.round(
                                board.left + (col + dx) * cellW);
                        int y = (int) Math.round(
                                board.top + (row + dy) * cellH);
                        x = Math.max(0, Math.min(w - 1, x));
                        y = Math.max(0, Math.min(h - 1, y));
                        int c = px[y * w + x];
                        sum[parity * 3] += (c >> 16) & 0xff;
                        sum[parity * 3 + 1] += (c >> 8) & 0xff;
                        sum[parity * 3 + 2] += c & 0xff;
                        count[parity]++;
                    }
                }
            }
        }
        if (count[0] == 0 || count[1] == 0) return 0;
        double dr = sum[0] / count[0] - sum[3] / count[1];
        double dg = sum[1] / count[0] - sum[4] / count[1];
        double db = sum[2] / count[0] - sum[5] / count[1];
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /** Read occupancy/highlights for an already-known board rect. */
    public Frame readBoard(int[] px, int w, int h, Rect board, boolean whiteAtBottom) {
        // A cached rect from a different screen size, or a detection
        // rounded past the edge, used to make meanColor sample
        // outside the bitmap and throw — now it is clamped first.
        board = new Rect(
                Math.max(0, board.left), Math.max(0, board.top),
                Math.min(w, board.right),
                Math.min(h, board.bottom));
        if (board.width() <= 0 || board.height() <= 0) return null;
        int[] occ = new int[64];
        List<Integer> highlights = new ArrayList<>();
        // v1.5.0: centre colour was computed twice per square —
        // once for occupancy, once for highlights — with the
        // identical inset. Compute it once and share it.
        // v1.8.0: two-pass, because highlights are now RELATIVE.
        // The old absolute test (yellow/green/blue centre) fired
        // on every bare light square of a wooden board — light
        // wood IS yellowish (200,165,110) — and on every light
        // square of a green board, so the highlight list was
        // 23-24 squares on the user's actual wooden Chess.com
        // board, never 2, and the highlight fast path in
        // inferMove / CaptureService never ran at all. Now:
        // first pass collects centre + background per square,
        // the median background per screen parity is the bare
        // square colour for that parity, and a square whose
        // background is far from its parity median is tinted.
        // Proven on the user's 2026-10-23 wooden screenshot:
        // c8 and d7 (the Bd7 pair) are the two farthest
        // backgrounds (40 and 36), the next square is 27, so
        // a threshold of 30 gives exactly the pair. Occupancy
        // corroboration in inferMove still guards against a
        // false pair, so a borderline tint cannot confirm a
        // move on its own.
        int[][] centres = new int[64][];
        int[][] bgs = new int[64][];
        for (int sq = 0; sq < 64; sq++) {
            Rect cell = squareRect(board, sq, whiteAtBottom);
            centres[sq] = meanColor(px, w, h, inset(cell, 0.42f));
            bgs[sq] = backgroundColor(px, w, h, cell);
        }
        int[][] medianBg = new int[2][3];
        for (int parity = 0; parity < 2; parity++) {
            for (int ch = 0; ch < 3; ch++) {
                int[] vals = new int[32];
                int n = 0;
                for (int sq = 0; sq < 64; sq++) {
                    int file = sq % 8, rank = sq / 8;
                    int col = whiteAtBottom ? file : 7 - file;
                    int row = whiteAtBottom ? 7 - rank : rank;
                    if ((row + col) % 2 != parity) continue;
                    vals[n++] = bgs[sq][ch];
                }
                java.util.Arrays.sort(vals, 0, n);
                medianBg[parity][ch] = vals[n / 2];
            }
        }
        double[] highlightDist = new double[64];
        for (int sq = 0; sq < 64; sq++) {
            int file = sq % 8, rank = sq / 8;
            int col = whiteAtBottom ? file : 7 - file;
            int row = whiteAtBottom ? 7 - rank : rank;
            int parity = (row + col) % 2;
            occ[sq] = classifyFrom(centres[sq], bgs[sq], parity);
            highlightDist[sq] = Math.sqrt(
                    Math.pow(bgs[sq][0] - medianBg[parity][0], 2)
                    + Math.pow(bgs[sq][1] - medianBg[parity][1], 2)
                    + Math.pow(bgs[sq][2] - medianBg[parity][2], 2));
        }
        // v1.12.0 adaptive highlights: the old absolute threshold
        // of 30 fails both ways across themes — a subtle tint
        // on a low-contrast board can be under 30, and wood
        // grain / texture can push empty squares over 30.
        // A last move is at most two squares (castling still
        // scores via occupancy, not highlights), so take the
        // two largest distances and accept them only if they
        // are clear outliers over the third-largest square.
        // That makes the tint test relative to the board's own
        // texture, not to an absolute colour.
        int first = -1, second = -1, third = -1;
        for (int sq = 0; sq < 64; sq++) {
            double d = highlightDist[sq];
            if (first < 0 || d > highlightDist[first]) {
                third = second; second = first; first = sq;
            } else if (second < 0 || d > highlightDist[second]) {
                third = second; second = sq;
            } else if (third < 0 || d > highlightDist[third]) {
                third = sq;
            }
        }
        if (first >= 0 && second >= 0) {
            double d1 = highlightDist[first];
            double d2 = highlightDist[second];
            double d3 = third >= 0 ? highlightDist[third] : 0;
            if (d1 >= 18 && d1 > 1.4 * Math.max(d3, 1)
                    && d2 >= 18) {
                highlights.add(Math.min(first, second));
                highlights.add(Math.max(first, second));
            } else if (d1 >= 30 && d2 >= 30) {
                highlights.add(Math.min(first, second));
                highlights.add(Math.max(first, second));
            }
        }
        return new Frame(board, occ, highlights);
    }

    /** Box-average downscale by an integer factor, for board
     *  detection. Replaces Bitmap.createScaledBitmap (Android)
     *  with the same effect on the pixel array: detection does
     *  not need full resolution, and averaging is closer to the
     *  filtered Android scaling than nearest-neighbour sampling.
     */
    static int[] downscale(int[] px, int w, int h, int scale) {
        int sw = w / scale, sh = h / scale;
        int[] out = new int[sw * sh];
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                long sr = 0, sg = 0, sb = 0;
                int n = 0;
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        int sx = x * scale + dx, sy = y * scale + dy;
                        if (sx >= w || sy >= h) continue;
                        int c = px[sy * w + sx];
                        sr += (c >> 16) & 0xff; sg += (c >> 8) & 0xff; sb += c & 0xff;
                        n++;
                    }
                }
                if (n == 0) n = 1;
                out[y * sw + x] = 0xff000000
                        | ((int) (sr / n) << 16)
                        | ((int) (sg / n) << 8)
                        | (int) (sb / n);
            }
        }
        return out;
    }

    // ---- board location -------------------------------------------------

    private Rect findBoard(int[] pxIn, int w, int h) {
        // Work on a downscaled copy: grid detection does not need full res.
        int scale = 1;
        while (w / scale > 540) scale *= 2;
        int sw = w / scale, sh = h / scale;
        int[] px = scale == 1 ? pxIn : downscale(pxIn, w, h, scale);
        double[] gray = new double[sw * sh];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            gray[i] = 0.299 * ((c >> 16) & 0xff)
                    + 0.587 * ((c >> 8) & 0xff) + 0.114 * (c & 0xff);
        }
        // Sobel edge strength, projected onto each row and column.
        double[] rowSum = new double[sh], colSum = new double[sw];
        for (int y = 1; y < sh - 1; y++) {
            for (int x = 1; x < sw - 1; x++) {
                double gx = gray[y * sw + x + 1] - gray[y * sw + x - 1];
                double gy = gray[(y + 1) * sw + x] - gray[(y - 1) * sw + x];
                double mag = Math.abs(gx) + Math.abs(gy);
                rowSum[y] += mag;
                colSum[x] += mag;
            }
        }
        int[] rows = findGridLines(rowSum, sh);
        int[] cols = findGridLines(colSum, sw);
        if (rows == null || cols == null) return null;
        return new Rect(cols[0] * scale, rows[0] * scale,
                cols[8] * scale, rows[8] * scale);
    }

    /**
     * Find 9 lines with near-equal spacing in a 1-D edge projection.
     * Peaks are local maxima above 40% of the strongest peak; the
     * search then looks for a 9-peak run whose gaps vary by < 15%.
     */
    private int[] findGridLines(double[] proj, int len) {
        double max = 0;
        for (double v : proj) max = Math.max(max, v);
        if (max <= 0) return null;
        List<Integer> peaks = new ArrayList<>();
        for (int i = 2; i < len - 2; i++) {
            if (proj[i] > 0.4 * max && proj[i] >= proj[i - 1]
                    && proj[i] >= proj[i + 1]
                    && proj[i] >= proj[i - 2] && proj[i] >= proj[i + 2]) {
                if (!peaks.isEmpty() && i - peaks.get(peaks.size() - 1) < 6) {
                    // Keep only the stronger of two close peaks.
                    int last = peaks.get(peaks.size() - 1);
                    if (proj[i] > proj[last]) peaks.set(peaks.size() - 1, i);
                } else {
                    peaks.add(i);
                }
            }
        }
        for (int start = 0; start + 8 < peaks.size(); start++) {
            int[] run = new int[9];
            for (int k = 0; k < 9; k++) run[k] = peaks.get(start + k);
            double gap = (run[8] - run[0]) / 8.0;
            if (gap < len * 0.04) continue; // board must be a decent size
            boolean even = true;
            for (int k = 1; k < 9; k++) {
                double g = run[k] - run[k - 1];
                if (Math.abs(g - gap) > 0.15 * gap) { even = false; break; }
            }
            if (even) return run;
        }
        return null;
    }

    // ---- checkerboard detection (v1.3.2) --------------------------------

    /**
     * Coarse-to-fine checkerboard search on a downscaled copy.
     * Coarse: try board sizes from full min-dimension down to half,
     * left = left / centred / right, top in steps, and score each
     * candidate by how cleanly its squares split into two colours
     * (corner samples, which mostly avoid centred pieces). A coarse
     * rect can be off by up to ~a quarter cell — corner samples
     * stay inside their square over that range, and back-rank
     * pieces bias the coarse optimum by a few pixels anyway — so
     * it is only a starting point. Fine: snap top/left/size by
     * maximising the actual edge strength at the 9 predicted
     * lines each way, restricted to the board's own range (so UI
     * edges outside the board cannot dominate, the way they did
     * in the global grid projection). Returns a full-res rect, or
     * null when no candidate looks like a board at all.
     */
    private Rect findBoardByCheckerboard(int[] pxIn, int w, int h) {
        int scale = 1;
        while (w / scale > 540) scale *= 2;
        int sw = w / scale, sh = h / scale;
        int[] px = scale == 1 ? pxIn : downscale(pxIn, w, h, scale);
        double[] gray = new double[sw * sh];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            gray[i] = 0.299 * ((c >> 16) & 0xff)
                    + 0.587 * ((c >> 8) & 0xff) + 0.114 * (c & 0xff);
        }

        int minDim = Math.min(sw, sh);
        // v1.5.0: down to 0.35, not just 0.5. A board in split
        // screen, or a Chess.com analysis board with a side panel,
        // can be well under half the screen width, and the old
        // list simply never tried those sizes. A smaller rect
        // inside a larger board does not out-score the true board:
        // its 8x8 grid cuts across real squares, so its parity
        // colours are mixed and its score is lower.
        double[] sizeFracs = {1.0, 0.95, 0.9, 0.85, 0.8, 0.7, 0.6, 0.5,
                0.4, 0.35};
        double bestScore = -1, bestDist = 0;
        int bestLeft = 0, bestTop = 0, bestSize = 0;
        for (double frac : sizeFracs) {
            int size = (int) Math.round(minDim * frac);
            if (size < 80) continue;
            int step = Math.max(4, size / 24);
            int[] lefts = {0, (sw - size) / 2, sw - size};
            for (int li = 0; li < 3; li++) {
                int left = lefts[li];
                if (left < 0) continue;
                boolean dup = false;
                for (int k = 0; k < li; k++) {
                    if (lefts[k] == left) dup = true;
                }
                if (dup) continue;
                for (int top = 0; top + size <= sh; top += step) {
                    double[] res = checkerScore(px, sw, sh, left, top, size);
                    if (res[0] > bestScore) {
                        bestScore = res[0];
                        bestDist = res[1];
                        bestLeft = left;
                        bestTop = top;
                        bestSize = size;
                    }
                }
            }
        }
        lastCheckerDist = bestDist;
        Rect result = null;
        if (bestSize > 0 && bestDist >= 25 && bestScore >= 12) {
            int[] refined = refineByEdges(
                    gray, sw, sh, bestLeft, bestTop, bestSize);
            int left = refined[0], top = refined[1], size = refined[2];
            if (size >= minDim * 0.3) {
                double[] res = checkerScore(px, sw, sh, left, top, size);
                if (res[1] >= 20) {
                    result = new Rect(
                            Math.max(0, left * scale),
                            Math.max(0, top * scale),
                            Math.min(w, (left + size) * scale),
                            Math.min(h, (top + size) * scale));
                    lastCheckerDist = res[1];
                }
            }
        }
        return result;
    }

    /**
     * Returns [score, colourDistance] for a candidate rect on the
     * downscaled image. Score = distance between the two parity
     * colour means, discounted by within-parity spread (pieces and
     * texture raise the spread, so a clean board scores highest).
     * Samples are the four near-corners of each square.
     */
    private double[] checkerScore(int[] px, int sw, int sh,
                                  int left, int top, int size) {
        double[] sum = new double[6]; // r,g,b per parity, interleaved
        double[] sumSq = new double[6];
        int[] count = new int[2];
        double cell = size / 8.0;
        double[] offs = {0.18, 0.82};
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                int parity = (row + col) % 2;
                for (double dx : offs) {
                    for (double dy : offs) {
                        int x = (int) Math.round(left + (col + dx) * cell);
                        int y = (int) Math.round(top + (row + dy) * cell);
                        x = Math.max(0, Math.min(sw - 1, x));
                        y = Math.max(0, Math.min(sh - 1, y));
                        int c = px[y * sw + x];
                        double r = (c >> 16) & 0xff;
                        double g = (c >> 8) & 0xff;
                        double b = c & 0xff;
                        sum[parity * 3] += r;
                        sum[parity * 3 + 1] += g;
                        sum[parity * 3 + 2] += b;
                        sumSq[parity * 3] += r * r;
                        sumSq[parity * 3 + 1] += g * g;
                        sumSq[parity * 3 + 2] += b * b;
                        count[parity]++;
                    }
                }
            }
        }
        if (count[0] == 0 || count[1] == 0) return new double[]{0, 0};
        double[] mean = new double[6];
        double std = 0;
        for (int p = 0; p < 2; p++) {
            for (int ch = 0; ch < 3; ch++) {
                mean[p * 3 + ch] = sum[p * 3 + ch] / count[p];
                double var = sumSq[p * 3 + ch] / count[p]
                        - mean[p * 3 + ch] * mean[p * 3 + ch];
                std += Math.sqrt(Math.max(0, var));
            }
        }
        std /= 6.0;
        double dist = Math.sqrt(
                Math.pow(mean[0] - mean[3], 2)
                + Math.pow(mean[1] - mean[4], 2)
                + Math.pow(mean[2] - mean[5], 2));
        return new double[]{dist / (1.0 + std / 20.0), dist};
    }

    /** Two passes of axis-separate edge fitting. Returns [left, top, size]. */
    private int[] refineByEdges(double[] gray, int sw, int sh,
                                int left, int top, int size) {
        for (int pass = 0; pass < 2; pass++) {
            // Horizontal: top + size, left fixed.
            int[] h = refineAxis(gray, sw, sh, left, top, size, true);
            top = h[0];
            size = h[1];
            // Vertical: left + size, top fixed.
            int[] v = refineAxis(gray, sw, sh, left, top, size, false);
            left = v[0];
            size = v[1];
        }
        return new int[]{left, top, size};
    }

    /**
     * Search one axis around the coarse rect for the strongest
     * predicted grid lines. Returns [start, size] for that axis.
     * Edge index for line k is start + k*cell - 1 (the diff between
     * the last pixel of one square and the first of the next);
     * lines at a screen border have no diff and are skipped.
     */
    private int[] refineAxis(double[] gray, int sw, int sh,
                             int left, int top, int size,
                             boolean horizontal) {
        int start = horizontal ? top : left;
        int other = horizontal ? left : top;
        int cell0 = Math.max(8, size / 8);
        int bestStart = start, bestSize = size;
        double bestScore = -1;
        int[] sizeDeltas = {-size / 20, -size / 40, 0, size / 40, size / 20};
        for (int dStart = -cell0 / 2; dStart <= cell0 / 2; dStart += 2) {
            for (int dSize : sizeDeltas) {
                int s = size + dSize;
                if (s < 80) continue;
                int st = start + dStart;
                if (st < 0) continue;
                if (horizontal && st + s > sh) continue;
                if (!horizontal && st + s > sw) continue;
                double score = edgeScore(gray, sw, sh,
                        horizontal ? other : st,
                        horizontal ? st : other,
                        s, horizontal);
                if (score > bestScore) {
                    bestScore = score;
                    bestStart = st;
                    bestSize = s;
                }
            }
        }
        return new int[]{bestStart, bestSize};
    }

    private double edgeScore(double[] gray, int sw, int sh,
                             int left, int top, int size,
                             boolean horizontal) {
        double cell = size / 8.0;
        double sum = 0;
        int lines = 0;
        for (int k = 0; k < 9; k++) {
            int edge = (int) Math.round(
                    (horizontal ? top : left) + k * cell) - 1;
            if (horizontal) {
                if (edge < 0 || edge + 1 >= sh) continue;
                double lineSum = 0;
                for (int x = left; x < left + size && x < sw; x++) {
                    lineSum += Math.abs(
                            gray[(edge + 1) * sw + x] - gray[edge * sw + x]);
                }
                sum += lineSum / Math.max(1, Math.min(size, sw - left));
                lines++;
            } else {
                if (edge < 0 || edge + 1 >= sw) continue;
                double lineSum = 0;
                for (int y = top; y < top + size && y < sh; y++) {
                    lineSum += Math.abs(
                            gray[y * sw + edge + 1] - gray[y * sw + edge]);
                }
                sum += lineSum / Math.max(1, Math.min(size, sh - top));
                lines++;
            }
        }
        return lines == 0 ? 0 : sum / lines;
    }

    // ---- per-square reading ----------------------------------------------

    /** Screen rect of one board square (sq in a1..h8 numbering). */
    public static Rect squareRect(Rect board, int sq, boolean whiteAtBottom) {
        int file = sq % 8, rank = sq / 8;
        int col = whiteAtBottom ? file : 7 - file;
        int row = whiteAtBottom ? 7 - rank : rank; // row 0 = top of screen
        float cellW = (board.right - board.left) / 8f;
        float cellH = (board.bottom - board.top) / 8f;
        return new Rect(
                Math.round(board.left + col * cellW),
                Math.round(board.top + row * cellH),
                Math.round(board.left + (col + 1) * cellW),
                Math.round(board.top + (row + 1) * cellH));
    }

    private static int[] meanColor(int[] px, int pw, int ph, Rect r) {
        // Sample a coarse grid inside r instead of every pixel: fast
        // enough to run every capture tick on a phone. Clamped to
        // the image: an unclamped rect used to throw
        // IllegalArgumentException from getPixel on Android.
        int left = Math.max(0, r.left), top = Math.max(0, r.top);
        int right = Math.min(pw, r.right);
        int bottom = Math.min(ph, r.bottom);
        if (right <= left || bottom <= top) return new int[]{0, 0, 0};
        long sr = 0, sg = 0, sb = 0;
        int n = 0;
        int stepX = Math.max(1, (right - left + 3) / 4);
        int stepY = Math.max(1, (bottom - top + 3) / 4);
        for (int y = top + stepY / 2; y < bottom; y += stepY) {
            for (int x = left + stepX / 2; x < right; x += stepX) {
                int c = px[y * pw + x];
                sr += (c >> 16) & 0xff; sg += (c >> 8) & 0xff; sb += c & 0xff;
                n++;
            }
        }
        if (n == 0) return new int[]{0, 0, 0};
        return new int[]{(int) (sr / n), (int) (sg / n), (int) (sb / n)};
    }

    private static Rect inset(Rect r, float frac) {
        int dx = Math.round(r.width() * frac), dy = Math.round(r.height() * frac);
        return new Rect(r.left + dx, r.top + dy, r.right - dx, r.bottom - dy);
    }

    private double centreLuminance(int[] px, int w, int h, Rect board, int sq,
                                   boolean whiteAtBottom) {
        Rect cell = squareRect(board, sq, whiteAtBottom);
        int[] c = meanColor(px, w, h, inset(cell, 0.42f));
        return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2];
    }

    private static int[] backgroundColor(int[] px, int pw, int ph, Rect cell) {
        // Square background: NOT the exact corner pixels. Chess.com
        // tiles have a dark border/shadow at the exact corners, so
        // a corner pixel reads the neighbouring tile's colour and
        // an empty light square looked "occupied" on a parity
        // pattern. Instead, take a small 3x3 patch inset 15% from
        // each corner, and take the median across the four patches
        // per channel, so one patch clipped by a wide piece base
        // cannot drag the background colour away.
        int[][] samples = new int[4][3];
        double[][] offs = {{0.15, 0.15}, {0.85, 0.15},
                {0.15, 0.85}, {0.85, 0.85}};
        for (int i = 0; i < 4; i++) {
            int cx = (int) Math.round(cell.left
                    + offs[i][0] * cell.width());
            int cy = (int) Math.round(cell.top
                    + offs[i][1] * cell.height());
            long sr = 0, sg = 0, sb = 0;
            int n = 0;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = Math.min(Math.max(cx + dx, 0), pw - 1);
                    int y = Math.min(Math.max(cy + dy, 0), ph - 1);
                    int c = px[y * pw + x];
                    sr += (c >> 16) & 0xff;
                    sg += (c >> 8) & 0xff;
                    sb += c & 0xff;
                    n++;
                }
            }
            samples[i][0] = (int) (sr / n);
            samples[i][1] = (int) (sg / n);
            samples[i][2] = (int) (sb / n);
        }
        int[] bg = new int[3];
        for (int ch = 0; ch < 3; ch++) {
            int[] v = new int[]{samples[0][ch], samples[1][ch],
                    samples[2][ch], samples[3][ch]};
            java.util.Arrays.sort(v);
            bg[ch] = (v[1] + v[2]) / 2;
        }
        return bg;
    }

    private static double colourDist(int[] a, double[] b) {
        return Math.sqrt(
                Math.pow(a[0] - b[0], 2)
                + Math.pow(a[1] - b[1], 2)
                + Math.pow(a[2] - b[2], 2));
    }

    private static double colourDist(int[] a, int[] b) {
        return Math.sqrt(
                Math.pow(a[0] - b[0], 2)
                + Math.pow(a[1] - b[1], 2)
                + Math.pow(a[2] - b[2], 2));
    }

    private int classifyFrom(int[] centre, int[] bg, int parity) {
        // v1.12.0 colour-agnostic classification. When calibrated,
        // the centre colour is compared in RGB to the calibrated
        // white piece, black piece, AND the square background,
        // and the nearest wins — BEFORE any "close to background
        // => empty" gate. The old order (empty gate first, at
        // distance < 38) is exactly what made beige-on-beige
        // and black-on-black boards unreadable: a beige white
        // piece on a beige square, or a near-black piece on a
        // near-black square, is close to its background by
        // definition, so it was always called empty, and
        // calibration could never land to fix it. With the
        // nearest-colour test, an empty square still wins
        // because its centre IS its background (distance ~0),
        // while a piece centre is its piece colour (distance
        // ~0 to the calibrated piece, larger to the background).
        if (rgbCalibrated) {
            double dEmpty = colourDist(centre, bg);
            double dWhite = colourDist(centre, whiteRgb[parity]);
            double dBlack = colourDist(centre, blackRgb[parity]);
            double best = Math.min(dEmpty, Math.min(dWhite, dBlack));
            if (best == dEmpty) return EMPTY;
            if (best > 90 && dEmpty < 60) return EMPTY;
            return best == dWhite ? WHITE_PIECE : BLACK_PIECE;
        }
        // Centre is a tight 0.42 inset (v1.8.0, was 0.35). A narrow
        // piece (a white pawn on Chess.com is beige and narrow)
        // still dominates the sample instead of being diluted by
        // square around it — and, proven on the user's wooden
        // screenshot, a tighter centre is far more stable when
        // the locked rect is a few pixels off (the detector's
        // downscaled refine step is ~4 full-res pixels): at
        // 0.35 a white pawn's luminance swung 163-208 across a
        // +/-2 px rect shift, crossing the uncalibrated
        // background threshold and flipping to black; at 0.42
        // it stays 208-224, and the whole board still reads
        // exactly at +/-2 px and +/-4 px shifts.
        double dist = Math.sqrt(
                Math.pow(centre[0] - bg[0], 2)
                + Math.pow(centre[1] - bg[1], 2)
                + Math.pow(centre[2] - bg[2], 2));
        if (dist < 38) return EMPTY; // centre looks like bare square
        double lum = 0.299 * centre[0] + 0.587 * centre[1] + 0.114 * centre[2];
        double bgLum = 0.299 * bg[0] + 0.587 * bg[1] + 0.114 * bg[2];
        if (whiteMean >= 0 && blackMean >= 0) {
            return Math.abs(lum - whiteMean) < Math.abs(lum - blackMean)
                    ? WHITE_PIECE : BLACK_PIECE;
        }
        // Uncalibrated fallback: a white piece is usually much brighter
        // than its square, a black piece much darker — but themes vary,
        // so this is only a first-frame guess until calibration lands.
        return lum > bgLum ? WHITE_PIECE : BLACK_PIECE;
    }

    // ---- move inference ---------------------------------------------------

    /**
     * Best legal move explaining this frame, or null. Highlights win
     * when they name exactly a legal from/to pair; otherwise the
     * occupancy change is scored against every legal move (departure
     * emptied, arrival filled/flipped, castling's two squares,
     * en passant's extra emptying).
     */
    public static ChessBoard.Move inferMove(ChessBoard board, int[] prev,
                                            int[] curr,
                                            List<Integer> highlights) {
        List<ChessBoard.Move> legal = board.legalMoves();
        if (highlights != null && highlights.size() == 2) {
            int a = highlights.get(0), b = highlights.get(1);
            for (ChessBoard.Move m : legal) {
                if ((m.from == a && m.to == b) || (m.from == b && m.to == a)) {
                    // v1.5.0: a highlight pair alone used to win
                    // even when the board had not changed at all.
                    // A UI tint, a stale highlight, or a theme
                    // that tints every square could confirm a move
                    // that never happened. Require at least one
                    // occupancy change corroborating the move
                    // (from emptied or to filled); otherwise fall
                    // through to occupancy scoring, which needs 2.
                    if (scoreMove(board, prev, curr, m) >= 1) {
                        return m;
                    }
                    break;
                }
            }
        }
        ChessBoard.Move best = null;
        int bestScore = -1;
        for (ChessBoard.Move m : legal) {
            int score = scoreMove(board, prev, curr, m);
            if (score > bestScore) { bestScore = score; best = m; }
        }
        // A real move changes at least the from-square and the to-square.
        return bestScore >= 2 ? best : null;
    }

    private static int scoreMove(ChessBoard board, int[] prev, int[] curr,
                                 ChessBoard.Move m) {
        int score = 0;
        int mover = board.sideToMove == 1 ? WHITE_PIECE : BLACK_PIECE;
        if (prev[m.from] == mover && curr[m.from] == EMPTY) score++;
        if (prev[m.to] != mover && curr[m.to] == mover) score++;
        // Castling: the rook also moves.
        if (Math.abs(board.board[m.from]) == 6
                && Math.abs(m.to - m.from) == 2) {
            int rookFrom = m.to > m.from ? m.from + 3 : m.from - 4;
            int rookTo = m.to > m.from ? m.from + 1 : m.from - 1;
            if (prev[rookFrom] == mover && curr[rookFrom] == EMPTY) score++;
            if (curr[rookTo] == mover) score++;
        }
        // En passant: the captured pawn's square empties too.
        if (Math.abs(board.board[m.from]) == 1 && m.to == board.enPassant
                && prev[m.to] == EMPTY) {
            int cap = m.to - board.sideToMove * 8;
            if (cap >= 0 && cap < 64 && prev[cap] != EMPTY
                    && curr[cap] == EMPTY) score++;
        }
        return score;
    }

    /** Occupancy the tracker *expects* from a known board position. */
    public static int[] expectedOccupancy(ChessBoard board) {
        int[] occ = new int[64];
        for (int i = 0; i < 64; i++) {
            occ[i] = board.board[i] == 0 ? EMPTY
                    : (board.board[i] > 0 ? WHITE_PIECE : BLACK_PIECE);
        }
        return occ;
    }

    public static int occupancyDelta(int[] a, int[] b) {
        if (a == null || b == null) return 99;
        int n = 0;
        for (int i = 0; i < 64; i++) if (a[i] != b[i]) n++;
        return n;
    }
}
