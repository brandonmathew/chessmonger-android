package com.chessmonger.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Position tracker, v2.0.0: the v1 CaptureService tracking logic,
 * extracted into a pure-Java state machine with NO Android imports.
 *
 * What moved here, and what did not:
 *  - Here: board lock, hidden-board guard, orientation trial,
 *    baseline confirmation, move confirmation (2 frames with
 *    highlights, 3 without), desync / stuck-waiting recovery,
 *    new-game detection (including checkmate pause), automatic
 *    recovery (exact, 1-2 move forward, start candidate, history
 *    rollback), calibration (including the v1.12 colour bootstrap),
 *    and the diagnostics log.
 *  - NOT here: screen capture, the overlay, Stockfish execution.
 *    The shell (CaptureService) owns those and acts on the
 *    Result this class returns: a status string, a suggestion
 *    to clear, a board rect to draw, or a FEN to analyse.
 *
 * Behaviour is deliberately identical to v1.12.0, including
 * every threshold (250 ms tick is the shell's, not this
 * class's; frame counts below are in frames at that tick):
 *  baseline confirmation 2 identical frames, move confirmation
 *  2/3 frames, desync after 8 stable diverging frames, hidden
 *  release after 24 frames, stuck-waiting recovery after 40
 *  frames, small-delta search retry every 20th stuck frame,
 *  recalibration every 50th exact frame, history capped at 50.
 *  Changing a threshold here changes live behaviour — do it
 *  in a test first (TrackerTest), not by editing a magic number.
 *
 * Square numbering, occupancy codes and move inference are
 * DetectorCore's, which this class uses throughout.
 */
public final class Tracker {

    /** What the shell should do after one processed frame. */
    public static final class Result {
        /** Status to show, or null for "no status change". */
        public final String status;
        /** Clear the overlay suggestion arrow. */
        public final boolean clearSuggestion;
        /** Clear the overlay board outline (lock released). */
        public final boolean clearBoard;
        /** Board rect newly locked, for overlay.setBoard, or null. */
        public final Rect boardRect;
        /** FEN the shell should analyse with Stockfish, or null. */
        public final String analyseFen;
        /** White at bottom, for the shell's overlay drawing. */
        public final boolean whiteAtBottom;

        public Result(String status, boolean clearSuggestion,
                      boolean clearBoard, Rect boardRect,
                      String analyseFen, boolean whiteAtBottom) {
            this.status = status;
            this.clearSuggestion = clearSuggestion;
            this.clearBoard = clearBoard;
            this.boardRect = boardRect;
            this.analyseFen = analyseFen;
            this.whiteAtBottom = whiteAtBottom;
        }

        public static Result statusOnly(String s, boolean whiteAtBottom) {
            return new Result(s, false, false, null, null, whiteAtBottom);
        }

        public static Result none(boolean whiteAtBottom) {
            return new Result(null, false, false, null, null, whiteAtBottom);
        }
    }

    public static final int BOARD_HIDDEN_LIMIT = 24;
    private static final int MAX_HISTORY = 50;

    private final DetectorCore detector = new DetectorCore();
    private final ChessBoard board = new ChessBoard();
    private final TrackerLog log = new TrackerLog();

    private int myColor = 1;
    private boolean whiteAtBottom = true;

    private Rect lockedBoard;
    private int lockedWidth, lockedHeight;
    private int boardHiddenFrames;

    private int[] baseline;
    private int[] prevOccupancy;
    private int[] firstOccupancy;
    private ChessBoard.Move pending;
    private int pendingCount;
    private int desyncCount;
    private int[] lastSmallDeltaSearchOccupancy;
    private int stuckFrames;
    private int newGameFrames;
    private boolean gameEnded;
    private boolean gameOverPaused;
    private boolean recovering;
    private boolean orientationLocked;
    private int exactMatchFrames;
    private String analysingFen;

    private final List<String> positionHistory = new ArrayList<>();

    public Tracker(int myColor) {
        this.myColor = myColor;
        this.whiteAtBottom = myColor != -1;
    }

    public DetectorCore detector() { return detector; }
    public ChessBoard board() { return board; }
    public TrackerLog log() { return log; }
    public String fen() { return board.toFen(); }
    public Rect lockedBoard() { return lockedBoard; }
    public boolean whiteAtBottom() { return whiteAtBottom; }
    public boolean gameOverPaused() { return gameOverPaused; }
    public int myColor() { return myColor; }

    public void setMyColor(int myColor) {
        this.myColor = myColor;
        this.whiteAtBottom = myColor != -1;
    }

    private void pushHistory() {
        String fen = board.toFen();
        if (!positionHistory.isEmpty()
                && positionHistory.get(positionHistory.size() - 1).equals(fen)) {
            return;
        }
        positionHistory.add(fen);
        while (positionHistory.size() > MAX_HISTORY) {
            positionHistory.remove(0);
        }
    }

    /** Full reset for a fresh Start, Resync, or FEN (see below
     *  for which of those also clear the pause / history). */
    private void clearTrackingState() {
        lockedBoard = null;
        lockedWidth = 0;
        lockedHeight = 0;
        boardHiddenFrames = 0;
        baseline = null;
        firstOccupancy = null;
        prevOccupancy = null;
        pending = null;
        pendingCount = 0;
        desyncCount = 0;
        analysingFen = null;
        lastSmallDeltaSearchOccupancy = null;
        stuckFrames = 0;
        // Deliberately NOT cleared: gameOverPaused, gameEnded,
        // newGameFrames. The board-hidden timeout calls this,
        // and clearing the pause there is how v1.9 lost a
        // checkmate pause when the win popup covered the board
        // for >6 s. Explicit Start / Resync / FEN clear them.
        recovering = false;
        orientationLocked = false;
        exactMatchFrames = 0;
        whiteAtBottom = myColor != -1;
    }

    /** Start in an already-running tracker: no stale lock. */
    public void start(String fen) {
        if (fen != null && !fen.isEmpty()) board.setFen(fen);
        else board.setFen(ChessBoard.START_FEN);
        gameOverPaused = false;
        gameEnded = false;
        newGameFrames = 0;
        clearTrackingState();
        positionHistory.clear();
        pushHistory();
        log.add("start", board.toFen(), "myColor=" + myColor);
    }

    public void resync() {
        gameOverPaused = false;
        gameEnded = false;
        newGameFrames = 0;
        clearTrackingState();
        log.add("resync", board.toFen(), "");
    }

    /** Returns false when the FEN is invalid (position kept). */
    public boolean setFen(String fen) {
        if (fen != null && board.setFen(fen)) {
            gameOverPaused = false;
            gameEnded = false;
            newGameFrames = 0;
            clearTrackingState();
            positionHistory.clear();
            pushHistory();
            log.add("setFen", board.toFen(), "");
            return true;
        }
        return false;
    }

    /** Shell: screen size changed (rotation). Keeps position
     *  and history, releases the lock, preserves recovery. */
    public void clearForSizeChange() {
        boolean wasRecovering = recovering;
        clearTrackingState();
        recovering = wasRecovering;
        log.add("sizeChange", board.toFen(), "");
    }

    public void clearAnalysingFen() { analysingFen = null; }

    public void clearAnalysingFenIfEquals(String fen) {
        if (fen != null && fen.equals(analysingFen)) analysingFen = null;
    }

    // ---- full frame: lock + hidden + detection + tracking ----

    public Result processPixels(int[] px, int w, int h) {
        DetectorCore.Frame frame;
        if (lockedBoard != null) {
            if (w != lockedWidth || h != lockedHeight) {
                lockedBoard = null;
            } else {
                frame = detector.readBoard(px, w, h, lockedBoard, whiteAtBottom);
                if (frame == null) {
                    lockedBoard = null;
                } else {
                    double dist = detector.checkerDistance(px, w, h, lockedBoard);
                    if (dist < 15) {
                        boardHiddenFrames++;
                        if (boardHiddenFrames >= BOARD_HIDDEN_LIMIT) {
                            clearTrackingState();
                            return new Result(
                                    gameOverPaused
                                            ? "Checkmate — paused until a new game starts"
                                            : "Board hidden — looking for board again…",
                                    false, true, null, null, whiteAtBottom);
                        } else if (boardHiddenFrames == 1) {
                            return Result.statusOnly(
                                    "Board hidden — holding position…", whiteAtBottom);
                        }
                        return Result.none(whiteAtBottom);
                    }
                    boardHiddenFrames = 0;
                    return processLocked(px, w, h, frame);
                }
            }
        }
        // No lock: detect once, then lock.
        frame = detector.detect(px, w, h, whiteAtBottom);
        if (frame == null) {
            return Result.statusOnly(
                    "ChessMonger: no board found — open a chess board"
                            + " (" + detector.lastMethod
                            + ", checker " + (int) detector.lastCheckerDist + ")",
                    whiteAtBottom);
        }
        lockedBoard = frame.board;
        lockedWidth = w;
        lockedHeight = h;
        boardHiddenFrames = 0;
        Rect newBoardRect = new Rect(frame.board);
        if (myColor == 0 && !orientationLocked) {
            orientationLocked = true;
            int[] expected = DetectorCore.expectedOccupancy(board);
            int normalDelta = DetectorCore.occupancyDelta(frame.occupancy, expected);
            DetectorCore.Frame flipped = detector.readBoard(
                    px, w, h, frame.board, !whiteAtBottom);
            if (flipped != null) {
                int flippedDelta = DetectorCore.occupancyDelta(
                        flipped.occupancy, expected);
                boolean normalClear = normalDelta <= 4
                        && flippedDelta >= normalDelta + 8;
                boolean flippedClear = flippedDelta <= 4
                        && normalDelta >= flippedDelta + 8;
                if (flippedClear && !normalClear) {
                    whiteAtBottom = !whiteAtBottom;
                    frame = flipped;
                }
            }
        } else {
            orientationLocked = true;
        }
        Result r = processLocked(px, w, h, frame);
        // The lock itself is new: tell the shell to draw it,
        // unless the locked result already cleared the board.
        if (r.clearBoard) return r;
        if (r.boardRect == null) {
            return new Result(r.status, r.clearSuggestion, false,
                    newBoardRect, r.analyseFen, r.whiteAtBottom);
        }
        return r;
    }

    // ---- recovery (exact, forward, start, history) ----

    private boolean tryRecover(int[] screenOccupancy) {
        int[] expected = DetectorCore.expectedOccupancy(board);
        if (DetectorCore.occupancyDelta(screenOccupancy, expected) == 0) {
            return true;
        }
        List<ChessBoard.Move> seq = ChessBoard.findSequenceToOccupancy(
                board, screenOccupancy, 2);
        if (seq != null && !seq.isEmpty()) {
            for (ChessBoard.Move m : seq) board.applyUnchecked(m);
            pushHistory();
            log.add("recoverForward", board.toFen(), "moves=" + seq.size());
            return true;
        }
        ChessBoard startBoard = new ChessBoard(ChessBoard.START_FEN);
        if (DetectorCore.occupancyDelta(screenOccupancy,
                DetectorCore.expectedOccupancy(startBoard)) == 0) {
            board.setFen(ChessBoard.START_FEN);
            positionHistory.clear();
            pushHistory();
            gameEnded = false;
            newGameFrames = 0;
            log.add("recoverStart", board.toFen(), "");
            return true;
        }
        List<ChessBoard.Move> startSeq = ChessBoard.findSequenceToOccupancy(
                startBoard, screenOccupancy, 2);
        if (startSeq != null && !startSeq.isEmpty()) {
            board.setFen(ChessBoard.START_FEN);
            positionHistory.clear();
            pushHistory();
            for (ChessBoard.Move m : startSeq) board.applyUnchecked(m);
            pushHistory();
            gameEnded = false;
            newGameFrames = 0;
            log.add("recoverStartForward", board.toFen(), "moves=" + startSeq.size());
            return true;
        }
        String matchFen = null;
        int matchDelta = Integer.MAX_VALUE;
        int secondDelta = Integer.MAX_VALUE;
        boolean ambiguous = false;
        for (int i = positionHistory.size() - 1; i >= 0; i--) {
            String fen = positionHistory.get(i);
            if (fen.equals(board.toFen())) continue;
            ChessBoard hist = new ChessBoard(fen);
            int d = DetectorCore.occupancyDelta(
                    screenOccupancy, DetectorCore.expectedOccupancy(hist));
            if (d <= 2) {
                if (d < matchDelta) {
                    secondDelta = matchDelta;
                    matchDelta = d;
                    matchFen = fen;
                    ambiguous = false;
                } else if (d == matchDelta && !fen.equals(matchFen)) {
                    ambiguous = true;
                } else if (d < secondDelta) {
                    secondDelta = d;
                }
            }
        }
        if (matchFen != null && !ambiguous
                && (matchDelta == 0 || secondDelta - matchDelta >= 3)) {
            if (board.setFen(matchFen)) {
                while (!positionHistory.isEmpty()
                        && !positionHistory.get(positionHistory.size() - 1)
                                .equals(matchFen)) {
                    positionHistory.remove(positionHistory.size() - 1);
                }
                log.add("recoverHistory", board.toFen(), "delta=" + matchDelta);
                return true;
            }
        }
        return false;
    }

    private static boolean matchesHighlights(
            java.util.List<Integer> highlights, ChessBoard.Move m) {
        if (highlights == null || highlights.size() != 2 || m == null) return false;
        int a = highlights.get(0), b = highlights.get(1);
        return (m.from == a && m.to == b) || (m.from == b && m.to == a);
    }

    /** Calibrate from a known board and re-read, keeping the new
     *  calibration only if the re-read matches better. Used for
     *  new games (known = start) and for the v1.12 colour
     *  bootstrap on a stable non-matching baseline frame. */
    private DetectorCore.Frame calibrateAndReread(
            int[] px, int w, int h, DetectorCore.Frame frame) {
        DetectorCore.Calibration snapshot = detector.snapshotCalibration();
        detector.calibrate(px, w, h, frame.board, board, whiteAtBottom);
        DetectorCore.Frame reread = detector.readBoard(px, w, h, frame.board, whiteAtBottom);
        if (reread == null) {
            detector.restoreCalibration(snapshot);
            return frame;
        }
        int[] expected = DetectorCore.expectedOccupancy(board);
        int oldDelta = DetectorCore.occupancyDelta(frame.occupancy, expected);
        int newDelta = DetectorCore.occupancyDelta(reread.occupancy, expected);
        if (newDelta < oldDelta) return reread;
        detector.restoreCalibration(snapshot);
        return frame;
    }

    // ---- tracking for a locked frame ----

    private Result processLocked(int[] px, int w, int h, DetectorCore.Frame frame) {
        int[] prevBefore = prevOccupancy;

        // Checkmate pause: ignore everything except a return
        // to the starting position.
        if (gameOverPaused) {
            int[] startExpected = DetectorCore.expectedOccupancy(
                    new ChessBoard(ChessBoard.START_FEN));
            boolean isStart = DetectorCore.occupancyDelta(
                    frame.occupancy, startExpected) == 0;
            if (isStart) newGameFrames++;
            else newGameFrames = 0;
            if (isStart && newGameFrames >= 2) {
                gameOverPaused = false;
                gameEnded = false;
                newGameFrames = 0;
                board.setFen(ChessBoard.START_FEN);
                positionHistory.clear();
                pushHistory();
                // New game may also be a new theme: calibrate
                // from this start frame and re-read.
                detector.calibrate(px, w, h, frame.board, board, whiteAtBottom);
                DetectorCore.Frame calibrated = detector.readBoard(
                        px, w, h, frame.board, whiteAtBottom);
                if (calibrated != null) frame = calibrated;
                baseline = frame.occupancy.clone();
                firstOccupancy = null;
                pending = null;
                pendingCount = 0;
                desyncCount = 0;
                stuckFrames = 0;
                analysingFen = null;
                log.add("newGame", board.toFen(), "fromPause");
                // Fall through with the baseline set.
            } else {
                prevOccupancy = frame.occupancy.clone();
                return new Result(
                        "Checkmate — paused until a new game starts",
                        true, false, null, null, whiteAtBottom);
            }
        }

        prevOccupancy = frame.occupancy.clone();
        boolean stableVsPrev = prevBefore != null
                && DetectorCore.occupancyDelta(prevBefore, frame.occupancy) == 0;

        // New game while not paused: screen back at the start.
        if (!board.isStartingPosition()) {
            boolean isStartFrame = DetectorCore.occupancyDelta(
                    frame.occupancy,
                    DetectorCore.expectedOccupancy(
                            new ChessBoard(ChessBoard.START_FEN))) == 0;
            if (isStartFrame) newGameFrames++;
            else newGameFrames = 0;
            if (isStartFrame && newGameFrames >= 2) {
                board.setFen(ChessBoard.START_FEN);
                positionHistory.clear();
                pushHistory();
                detector.calibrate(px, w, h, frame.board, board, whiteAtBottom);
                DetectorCore.Frame calibrated = detector.readBoard(
                        px, w, h, frame.board, whiteAtBottom);
                if (calibrated != null) frame = calibrated;
                baseline = frame.occupancy.clone();
                firstOccupancy = null;
                pending = null;
                pendingCount = 0;
                desyncCount = 0;
                stuckFrames = 0;
                analysingFen = null;
                gameEnded = false;
                newGameFrames = 0;
                log.add("newGame", board.toFen(), "");
                // Fall through: baseline is set, tracking resumes
                // on this frame below.
            }
        } else {
            newGameFrames = 0;
        }

        if (baseline == null) {
            if (firstOccupancy == null
                    || DetectorCore.occupancyDelta(firstOccupancy, frame.occupancy) != 0) {
                firstOccupancy = frame.occupancy.clone();
                return Result.statusOnly(
                        recovering
                                ? "Out of sync — trying to recover automatically…"
                                : "Board locked in — confirming position…",
                        whiteAtBottom);
            }
            int[] expected = DetectorCore.expectedOccupancy(board);
            int delta = DetectorCore.occupancyDelta(frame.occupancy, expected);
            if (delta > 4) {
                DetectorCore.Frame improved = calibrateAndReread(px, w, h, frame);
                if (improved != frame) {
                    frame = improved;
                    delta = DetectorCore.occupancyDelta(frame.occupancy, expected);
                }
            }
            boolean recovered = false;
            if (recovering || delta > 4) {
                recovered = tryRecover(frame.occupancy);
            }
            if (!recovered && delta > 4) {
                recovering = true;
                return Result.statusOnly(
                        "Out of sync — trying to recover automatically "
                                + "(paste a FEN if it stays stuck)",
                        whiteAtBottom);
            }
            if (recovered && delta > 4) {
                log.add("recovered", board.toFen(), "");
            }
            int[] finalExpected = DetectorCore.expectedOccupancy(board);
            if (DetectorCore.occupancyDelta(frame.occupancy, finalExpected) <= 4) {
                detector.calibrate(px, w, h, frame.board, board, whiteAtBottom);
            }
            baseline = frame.occupancy.clone();
            firstOccupancy = null;
            pending = null;
            pendingCount = 0;
            stuckFrames = 0;
            recovering = false;
            return Result.statusOnly(
                    recovered && delta > 4
                            ? "Recovered — tracking again"
                            : "Board locked in — watching for moves…",
                    whiteAtBottom);
        }

        ChessBoard.Move candidate = DetectorCore.inferMove(
                board, baseline, frame.occupancy, frame.highlights);
        if (candidate != null) {
            if (candidate.equals(pending)) pendingCount++;
            else {
                pending = candidate;
                pendingCount = 1;
            }
            int requiredFrames = matchesHighlights(frame.highlights, pending) ? 2 : 3;
            if (pendingCount >= requiredFrames) {
                board.applyUci(pending.uci());
                pushHistory();
                baseline = frame.occupancy.clone();
                pending = null;
                pendingCount = 0;
                desyncCount = 0;
                stuckFrames = 0;
                analysingFen = null;
                log.add("move", board.toFen(), "pendingConfirmed");
                return new Result(null, true, false, null, null, whiteAtBottom);
            }
            return Result.none(whiteAtBottom);
        }

        int delta = DetectorCore.occupancyDelta(
                frame.occupancy, DetectorCore.expectedOccupancy(board));
        if (delta == 0) {
            pending = null;
            pendingCount = 0;
            desyncCount = 0;
            stuckFrames = 0;
            exactMatchFrames++;
            if (exactMatchFrames >= 50) {
                exactMatchFrames = 0;
                detector.calibrate(px, w, h, frame.board, board, whiteAtBottom);
            }
        } else if (delta >= 4) {
            if (stableVsPrev) desyncCount++;
            if (desyncCount >= 8) {
                recovering = true;
                baseline = null;
                firstOccupancy = null;
                pending = null;
                pendingCount = 0;
                desyncCount = 0;
                analysingFen = null;
                log.add("desync", board.toFen(), "delta=" + delta);
                return new Result(
                        "Out of sync — trying to recover automatically…",
                        true, false, null, null, whiteAtBottom);
            }
            return Result.none(whiteAtBottom);
        } else {
            // Small delta (1-3), no legal move: stuck watchdog,
            // then a depth-1 forward search once per distinct
            // screen (or every 20th stuck frame), otherwise noise.
            stuckFrames++;
            if (stuckFrames >= 40) {
                recovering = true;
                baseline = null;
                firstOccupancy = null;
                pending = null;
                pendingCount = 0;
                desyncCount = 0;
                stuckFrames = 0;
                analysingFen = null;
                log.add("stuck", board.toFen(), "delta=" + delta);
                return new Result(
                        "Stuck waiting — trying to recover automatically…",
                        true, false, null, null, whiteAtBottom);
            }
            boolean periodicRetry = stuckFrames >= 20 && stuckFrames % 20 == 0;
            if ((stableVsPrev
                    && (lastSmallDeltaSearchOccupancy == null
                            || DetectorCore.occupancyDelta(
                                    lastSmallDeltaSearchOccupancy,
                                    frame.occupancy) != 0))
                    || periodicRetry) {
                lastSmallDeltaSearchOccupancy = frame.occupancy.clone();
                List<ChessBoard.Move> seq = ChessBoard.findSequenceToOccupancy(
                        board, frame.occupancy, 1);
                if (seq != null && !seq.isEmpty()) {
                    for (ChessBoard.Move m : seq) board.applyUnchecked(m);
                    pushHistory();
                    baseline = frame.occupancy.clone();
                    pending = null;
                    pendingCount = 0;
                    desyncCount = 0;
                    analysingFen = null;
                    log.add("missedMove", board.toFen(), "");
                    return Result.statusOnly(
                            "Recovered a missed move — tracking again", whiteAtBottom);
                }
            }
            pending = null;
            pendingCount = 0;
            desyncCount = 0;
            // Fall through to analysis / waiting: FEN trusted.
        }

        if (myColor != 0 && board.sideToMove != myColor) {
            return new Result(
                    "Waiting for opponent… · move " + board.fullmove,
                    true, false, null, null, whiteAtBottom);
        }
        if (board.legalMoves().isEmpty() && board.inCheck(board.sideToMove)) {
            gameOverPaused = true;
            gameEnded = true;
            log.add("checkmate", board.toFen(), "");
            return new Result(
                    "Checkmate — paused until a new game starts",
                    true, false, null, null, whiteAtBottom);
        }
        if (board.isGameOver()) {
            gameEnded = true;
            return Result.statusOnly(board.gameOverText(), whiteAtBottom);
        }
        String fen = board.toFen();
        if (fen.equals(analysingFen)) return Result.none(whiteAtBottom);
        analysingFen = fen;
        return new Result("Thinking…", false, false, null, fen, whiteAtBottom);
    }
}
