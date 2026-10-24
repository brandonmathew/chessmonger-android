package com.chessmonger.android;

import android.app.ActivityManager;
import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Stockfish 17.1 (GPLv3, stockfishchess.org) as a separate process,
 * driven over UCI. The binary ships inside the APK as
 * libstockfish.so (a PIE executable, see STOCKFISH.md) and is run
 * from the app's native library directory — the same packaging
 * trick proven in Gambit Chess, so no JNI binding is needed.
 *
 * Max strength, on purpose: no skill cap, no Elo limit, MultiPV 1,
 * all the phone's cores, and a large hash. The only limit is time:
 * {@code movetimeMs} from the UI. Longer think = deeper search =
 * stronger move, at the cost of waiting longer for the arrow.
 * One process serves the whole app; calls are serialised.
 */
public final class StockfishEngine {

    public static final class Result {
        public String bestMove;   // UCI, e.g. "e2e4" or "e7e8q"
        public String ponder;
        public int depth;
        public int scoreCp;       // from the side-to-move's view
        public int mate;          // 0 = no mate, else mate in N (signed)
        public List<String> pv = new ArrayList<>();

        public String evalText() {
            if (mate != 0) return mate > 0 ? "Mate in " + mate
                    : "Mated in " + (-mate);
            int cp = scoreCp;
            String sign = cp >= 0 ? "+" : "-";
            return sign + String.format("%.2f", Math.abs(cp) / 100.0);
        }
    }

    private static StockfishEngine instance;
    private static boolean startFailed;
    private static long lastStartAttemptMs;

    public static synchronized StockfishEngine get(Context context) {
        // v1.5.0: the old code returned a dead instance forever.
        // Once Stockfish died (OOM, system kill, a failed search
        // that called kill()), analyze() returned null on every
        // frame and suggestions never came back until the whole
        // app was restarted. A dead instance is now discarded and
        // a fresh process is started on the next request.
        if (instance != null) {
            if (instance.alive()) return instance;
            try { instance.kill(); } catch (Exception ignored) {}
            instance = null;
        }
        // v1.8.0: the old code latched startFailed forever, so
        // one transient failure (OOM, a slow first start) killed
        // suggestions for the whole session. Retry after 10 s
        // instead — a missing binary still fails fast, so the
        // retry is cheap, and a transient failure recovers.
        if (startFailed
                && System.currentTimeMillis() - lastStartAttemptMs < 10000) {
            return null;
        }
        lastStartAttemptMs = System.currentTimeMillis();
        try {
            File binary = new File(
                    context.getApplicationInfo().nativeLibraryDir,
                    "libstockfish.so");
            if (!binary.exists()) { startFailed = true; return null; }
            instance = new StockfishEngine(binary, hashForDevice(context));
            startFailed = false;
            return instance;
        } catch (Exception e) {
            startFailed = true;
            return null;
        }
    }

    /**
     * Hash scaled to the phone, not a fixed 256 MB. A 256 MB hash
     * plus 8 threads is fine on a 12 GB Galaxy S25, but on a
     * 2-3 GB Android 8 device in the supported range it is a
     * realistic OOM kill for the engine process — which used to
     * mean suggestions stopped for the whole session.
     */
    private static int hashForDevice(Context context) {
        try {
            ActivityManager am = (ActivityManager)
                    context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi =
                        new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long gb = mi.totalMem / (1024L * 1024L * 1024L);
                if (gb >= 6) return 256;
                if (gb >= 3) return 128;
                return 64;
            }
        } catch (Exception ignored) {
        }
        return 128;
    }

    public static synchronized void shutdown() {
        if (instance != null) instance.kill();
        instance = null;
        startFailed = false;
        lastStartAttemptMs = 0;
    }

    private final Process process;
    private final OutputStreamWriter input;
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final Object lock = new Object();
    private volatile boolean dead;

    private StockfishEngine(File binary, int hashMb) throws Exception {
        // The extracted .so needs the executable bit on some devices.
        // setExecutable is harmless when it is already executable.
        try { binary.setExecutable(true); } catch (Exception ignored) {}
        process = new ProcessBuilder(binary.getAbsolutePath())
                .redirectErrorStream(true)
                .start();
        input = new OutputStreamWriter(process.getOutputStream(),
                StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8));
        Thread pump = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.offer(line);
                    }
                } catch (Exception ignored) {
                    // Process ended; the queue simply stops filling.
                }
                dead = true;
            }
        });
        pump.setDaemon(true);
        pump.start();

        send("uci");
        expect("uciok", 8000);
        // Max strength: full skill, no Elo cap, all cores, big hash.
        int threads = Math.max(1, Math.min(8,
                Runtime.getRuntime().availableProcessors()));
        send("setoption name Threads value " + threads);
        send("setoption name Hash value " + hashMb);
        send("setoption name Skill Level value 20");
        send("setoption name UCI_LimitStrength value false");
        send("setoption name MultiPV value 1");
        send("setoption name UCI_ShowWDL value true");
        send("isready");
        expect("readyok", 8000);
    }

    public boolean alive() { return !dead; }

    /** Analyse a FEN for roughly movetimeMs. Null on any failure. */
    public Result analyze(String fen, int movetimeMs) {
        synchronized (lock) {
            if (dead) return null;
            try {
                return analyzeLocked(fen, movetimeMs);
            } catch (Exception e) {
                kill();
                return null;
            }
        }
    }

    private Result analyzeLocked(String fen, int movetimeMs) throws Exception {
        while (lines.poll() != null) { /* drain stale lines */ }
        send("position fen " + fen);
        send("go movetime " + movetimeMs);

        Result result = new Result();
        long deadline = System.currentTimeMillis() + movetimeMs + 8000;
        boolean stopped = false;
        while (true) {
            long wait = deadline - System.currentTimeMillis();
            if (wait <= 0) {
                if (!stopped) {
                    send("stop");
                    stopped = true;
                    deadline = System.currentTimeMillis() + 2000;
                    continue;
                }
                kill();
                return null;
            }
            String line = lines.poll(wait, TimeUnit.MILLISECONDS);
            if (line == null) continue;
            if (line.startsWith("bestmove")) {
                String[] parts = line.split("\\s+");
                if (parts.length < 2 || "(none)".equals(parts[1])) return null;
                result.bestMove = parts[1];
                if (parts.length >= 4 && "ponder".equals(parts[2])) {
                    result.ponder = parts[3];
                }
                break;
            }
            if (line.startsWith("info") && line.contains(" pv ")) {
                parseInfo(line, result);
            }
        }
        return result.bestMove == null ? null : result;
    }

    private void parseInfo(String line, Result out) {
        try {
            String[] t = line.split("\\s+");
            int depth = 0, scoreCp = 0, mate = 0;
            List<String> pv = new ArrayList<>();
            boolean inPv = false;
            for (int i = 0; i < t.length; i++) {
                if ("depth".equals(t[i]) && i + 1 < t.length) {
                    depth = Integer.parseInt(t[i + 1]);
                } else if ("mate".equals(t[i]) && i + 1 < t.length) {
                    mate = Integer.parseInt(t[i + 1]);
                } else if ("cp".equals(t[i]) && i + 1 < t.length) {
                    scoreCp = Integer.parseInt(t[i + 1]);
                } else if ("pv".equals(t[i])) {
                    inPv = true;
                } else if (inPv) {
                    pv.add(t[i]);
                }
            }
            if (!pv.isEmpty() && depth >= out.depth) {
                out.depth = depth;
                out.scoreCp = scoreCp;
                out.mate = mate;
                out.pv = pv;
            }
        } catch (Exception ignored) {
            // A malformed info line never kills the search.
        }
    }

    private void send(String command) throws Exception {
        input.write(command + "\n");
        input.flush();
    }

    private void expect(String token, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            String line = lines.poll(
                    deadline - System.currentTimeMillis(),
                    TimeUnit.MILLISECONDS);
            if (line != null && line.contains(token)) return;
        }
        throw new IllegalStateException("Stockfish did not say " + token);
    }

    public synchronized void kill() {
        dead = true;
        try { send("quit"); } catch (Exception ignored) {}
        try { process.destroy(); } catch (Exception ignored) {}
        // destroy() is only a request: if the process does not
        // exit promptly, force it, or a zombie engine can hold
        // the hash memory and block the next start.
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
        }
    }
}
