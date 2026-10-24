package com.chessmonger.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Stockfish configuration, v2.1.0 — pure Java, no Android.
 *
 * Before v2.1.0 the engine was hard-coded to max strength and
 * the only user control was think time. This class is the
 * single source of truth for every UCI option the UI exposes,
 * with defaults, clamping, and the exact setoption commands
 * the engine needs. The Android shell persists it (via
 * SharedPreferences) and passes it to StockfishEngine on
 * Start; changing it while running applies on the next Start,
 * which is the predictable rule (the engine process is created
 * with these options, and restarting it mid-game would drop
 * the current search).
 *
 * Defaults are the old behaviour: max strength.
 *  thinkMs 2000 (500–10000)
 *  skillLevel 20 (0–20, 20 = strongest)
 *  limitStrength false; when true, UCI_Elo caps strength
 *    (1320–3190) and Skill Level is largely irrelevant —
 *    the UI says so, and the engine still receives both.
 *  multipv 1 (1–5; >1 shows alternatives on the overlay)
 *  threadsAuto true; manual threads 1–8
 *  hashAuto true; manual hash 16–1024 MB
 *  contempt 0 (-100–100; positive = prefer avoiding draws)
 */
public final class EngineSettings {

    public int thinkMs = 2000;
    public int skillLevel = 20;
    public boolean limitStrength = false;
    public int elo = 1800;
    public int multipv = 1;
    public boolean threadsAuto = true;
    public int threads = 4;
    public boolean hashAuto = true;
    public int hashMb = 128;
    public int contempt = 0;

    public EngineSettings() {}

    public EngineSettings(EngineSettings other) {
        this.thinkMs = other.thinkMs;
        this.skillLevel = other.skillLevel;
        this.limitStrength = other.limitStrength;
        this.elo = other.elo;
        this.multipv = other.multipv;
        this.threadsAuto = other.threadsAuto;
        this.threads = other.threads;
        this.hashAuto = other.hashAuto;
        this.hashMb = other.hashMb;
        this.contempt = other.contempt;
    }

    /** Clamp every field to its valid range, in place. */
    public void clamp() {
        if (thinkMs < 500) thinkMs = 500;
        if (thinkMs > 10000) thinkMs = 10000;
        if (skillLevel < 0) skillLevel = 0;
        if (skillLevel > 20) skillLevel = 20;
        if (elo < 1320) elo = 1320;
        if (elo > 3190) elo = 3190;
        if (multipv < 1) multipv = 1;
        if (multipv > 5) multipv = 5;
        if (threads < 1) threads = 1;
        if (threads > 8) threads = 8;
        if (hashMb < 16) hashMb = 16;
        if (hashMb > 1024) hashMb = 1024;
        if (contempt < -100) contempt = -100;
        if (contempt > 100) contempt = 100;
    }

    /** UCI setoption commands for a fresh engine process.
     *  Threads/hash are already resolved (auto -> device value)
     *  by the caller, because this class must not touch Android
     *  APIs to read RAM or CPU count. */
    public List<String> uciOptions(int resolvedThreads, int resolvedHashMb) {
        clamp();
        List<String> out = new ArrayList<>();
        out.add("setoption name Threads value " + resolvedThreads);
        out.add("setoption name Hash value " + resolvedHashMb);
        out.add("setoption name Skill Level value " + skillLevel);
        out.add("setoption name UCI_LimitStrength value " + limitStrength);
        out.add("setoption name UCI_Elo value " + elo);
        out.add("setoption name MultiPV value " + multipv);
        out.add("setoption name Contempt value " + contempt);
        out.add("setoption name UCI_ShowWDL value true");
        return out;
    }

    /** One-line summary for the UI, e.g. "Max strength · 2.0s". */
    public String summary() {
        String strength = limitStrength
                ? "Elo " + elo
                : (skillLevel >= 20 ? "Max strength" : "Skill " + skillLevel);
        String extra = multipv > 1 ? " · MultiPV " + multipv : "";
        return strength + " · " + (thinkMs / 1000.0) + "s" + extra;
    }
}
