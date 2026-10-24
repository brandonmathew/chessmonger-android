package com.chessmonger.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Ring buffer of tracker events, for diagnostics.
 *
 * v1 had no tracker log at all: a freeze could only be reported
 * as a screenshot and a status line. v2 records every state
 * transition, confirmed move, recovery, desync, new game and
 * checkmate pause, with the FEN and the occupancy delta at the
 * time. MainActivity shares this log (Share / Copy) the same
 * way it shares a crash report, so a future "it froze here"
 * report can include what the tracker actually did, not just
 * what the overlay showed.
 *
 * Bounded (200 entries) and thread-safe for the capture +
 * engine threads that both append through Tracker.
 */
public final class TrackerLog {
    public static final int MAX = 200;

    public static final class Entry {
        public final long timeMs;
        public final String kind;
        public final String fen;
        public final String detail;

        public Entry(long timeMs, String kind, String fen, String detail) {
            this.timeMs = timeMs;
            this.kind = kind;
            this.fen = fen;
            this.detail = detail;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public synchronized void add(String kind, String fen, String detail) {
        entries.add(new Entry(System.currentTimeMillis(), kind, fen, detail));
        while (entries.size() > MAX) entries.remove(0);
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    public synchronized void clear() {
        entries.clear();
    }

    @Override public synchronized String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ChessMonger tracker log (").append(entries.size())
                .append(" entries, newest last)\n");
        for (Entry e : entries) {
            sb.append(e.timeMs).append(" ").append(e.kind)
                    .append(" fen=").append(e.fen)
                    .append(" ").append(e.detail).append("\n");
        }
        return sb.toString();
    }
}
