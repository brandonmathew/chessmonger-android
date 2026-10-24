package com.chessmonger.core;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.List;

public class EngineSettingsTest {

    @Test public void defaultsAreMaxStrength() {
        EngineSettings s = new EngineSettings();
        assertEquals(2000, s.thinkMs);
        assertEquals(20, s.skillLevel);
        assertFalse(s.limitStrength);
        assertEquals(1, s.multipv);
        assertTrue(s.threadsAuto);
        assertTrue(s.hashAuto);
        assertEquals(0, s.contempt);
        assertEquals("Max strength · 2.0s", s.summary());
        List<String> cmds = s.uciOptions(8, 256);
        assertTrue(cmds.contains("setoption name Skill Level value 20"));
        assertTrue(cmds.contains("setoption name UCI_LimitStrength value false"));
        assertTrue(cmds.contains("setoption name MultiPV value 1"));
        assertTrue(cmds.contains("setoption name Threads value 8"));
        assertTrue(cmds.contains("setoption name Hash value 256"));
    }

    @Test public void clampKeepsSettingsInRange() {
        EngineSettings s = new EngineSettings();
        s.thinkMs = 999999; s.skillLevel = -5; s.elo = 9999;
        s.multipv = 99; s.threads = 0; s.hashMb = 1; s.contempt = 500;
        s.clamp();
        assertEquals(10000, s.thinkMs);
        assertEquals(0, s.skillLevel);
        assertEquals(3190, s.elo);
        assertEquals(5, s.multipv);
        assertEquals(1, s.threads);
        assertEquals(16, s.hashMb);
        assertEquals(100, s.contempt);
    }

    @Test public void limitedSummaryShowsElo() {
        EngineSettings s = new EngineSettings();
        s.limitStrength = true;
        s.elo = 1500;
        s.multipv = 3;
        assertEquals("Elo 1500 · 2.0s · MultiPV 3", s.summary());
    }

    @Test public void uciParserParsesCpAndMateAndMultipv() {
        UciParser.Info a = UciParser.parse(
                "info depth 20 seldepth 28 multipv 1 score cp 45 nodes 123 pv e2e4 e7e5 g1f3");
        assertNotNull(a);
        assertEquals(1, a.multipv);
        assertEquals(20, a.depth);
        assertEquals(45, a.scoreCp);
        assertEquals(0, a.mate);
        assertEquals("e2e4", a.pv.get(0));
        UciParser.Info b = UciParser.parse(
                "info depth 18 multipv 2 score mate 3 pv d2d4 d7d5");
        assertNotNull(b);
        assertEquals(2, b.multipv);
        assertEquals(3, b.mate);
        assertNull(UciParser.parse("info depth 20 score cp 10 nodes 1"));
        assertNull(UciParser.parse("bestmove e2e4"));
    }
}
