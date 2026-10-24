package com.chessmonger.core;

import java.util.ArrayList;
import java.util.List;

/**
 * UCI info-line parsing, extracted from StockfishEngine so it
 * can be tested on the JVM without Android or a process.
 *
 * A Stockfish info line looks like:
 *   info depth 20 seldepth 28 multipv 1 score cp 45 nodes ... pv e2e4 e7e5 ...
 *   info depth 20 multipv 2 score mate 3 pv d2d4 ...
 * Fields may appear in any order, and "score cp" / "score mate"
 * are mutually exclusive. Lines without "pv" are not moves
 * (they are depth/nodes updates) and return null.
 */
public final class UciParser {

    public static final class Info {
        public int multipv = 1;
        public int depth = 0;
        public int scoreCp = 0;
        public int mate = 0;
        public List<String> pv = new ArrayList<>();
    }

    public static Info parse(String line) {
        if (line == null || !line.startsWith("info") || !line.contains(" pv ")) {
            return null;
        }
        Info out = new Info();
        String[] t = line.split("\\s+");
        boolean inPv = false;
        try {
            for (int i = 0; i < t.length; i++) {
                if ("multipv".equals(t[i]) && i + 1 < t.length) {
                    out.multipv = Integer.parseInt(t[i + 1]);
                } else if ("depth".equals(t[i]) && i + 1 < t.length) {
                    out.depth = Integer.parseInt(t[i + 1]);
                } else if ("mate".equals(t[i]) && i + 1 < t.length) {
                    out.mate = Integer.parseInt(t[i + 1]);
                } else if ("cp".equals(t[i]) && i + 1 < t.length) {
                    out.scoreCp = Integer.parseInt(t[i + 1]);
                } else if ("pv".equals(t[i])) {
                    inPv = true;
                } else if (inPv) {
                    out.pv.add(t[i]);
                }
            }
        } catch (Exception ignored) {
            // A malformed info line never kills the search.
            return null;
        }
        if (out.pv.isEmpty()) return null;
        return out;
    }
}
