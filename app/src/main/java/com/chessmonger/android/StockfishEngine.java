package com.chessmonger.android;

import android.app.ActivityManager;
import android.content.Context;

import com.chessmonger.core.EngineSettings;
import com.chessmonger.core.UciParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * v2.1.0: configurable via core.EngineSettings (see that class
 * for defaults and ranges). The old hard-coded max strength is
 * still the default, so existing callers that pass no settings
 * get exactly the old behaviour. One process serves the whole
 * app; calls are serialised. Settings apply at process start;
 * changing them in the UI applies on the next Start.
 */
public final class StockfishEngine {

    public static final class Result {
        public String bestMove;   // UCI, e.g. "e2e4" or "e7e8q"
        public String ponder;
        public int depth;
        public int scoreCp;       // from the side-to-move's view
        public int mate;          // 0 = no mate, else mate in N (signed)
        public List<String> pv = new ArrayList<>();
        /** MultiPV alternatives (pv 2..N), empty when MultiPV = 1. */
        public List<Result> alternatives = new ArrayList<>();

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
        return get(context, new EngineSettings());
    }

    public static synchronized StockfishEngine get(Context context, EngineSettings settings) {
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
            if (settings == null) settings = new EngineSettings();
            settings.clamp();
            int threads = settings.threadsAuto
                    ? Math.max(1, Math.min(8,
                            Runtime.getRuntime().availableProcessors()))
                    : settings.threads;
            int hashMb = settings.hashAuto
                    ? hashForDevice(context) : settings.hashMb;
            instance = new StockfishEngine(binary, settings, threads, hashMb);
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

    private StockfishEngine(File binary, EngineSettings settings,
                            int threads, int hashMb) throws Exception {
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
        for (String cmd : settings.uciOptions(threads, hashMb)) {
            send(cmd);
        }
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

        // Per-MultiPV best info at the deepest depth seen.
        // The main result is multipv 1; the rest become
        // alternatives, in multipv order, for the overlay.
        Map<Integer, Result> pvResults = new HashMap<>();
        String bestMove = null;
        String ponder = null;
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
                bestMove = parts[1];
                if (parts.length >= 4 && "ponder".equals(parts[2])) {
                    ponder = parts[3];
                }
                break;
            }
            if (line.startsWith("info") && line.contains(" pv ")) {
                UciParser.Info info = UciParser.parse(line);
                if (info == null) continue;
                Result existing = pvResults.get(info.multipv);
                if (existing == null || info.depth >= existing.depth) {
                    Result r = new Result();
                    r.depth = info.depth;
                    r.scoreCp = info.scoreCp;
                    r.mate = info.mate;
                    r.pv = new ArrayList<>(info.pv);
                    r.bestMove = info.pv.get(0);
                    pvResults.put(info.multipv, r);
                }
            }
        }
        Result result = pvResults.get(1);
        if (result == null) {
            result = new Result();
            result.bestMove = bestMove;
        } else {
            result.bestMove = bestMove;
        }
        result.ponder = ponder;
        for (int i = 2; i <= 5; i++) {
            Result alt = pvResults.get(i);
            if (alt != null) result.alternatives.add(alt);
        }
        return result.bestMove == null ? null : result;
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
