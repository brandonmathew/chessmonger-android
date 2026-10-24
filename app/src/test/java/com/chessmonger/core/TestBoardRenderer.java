package com.chessmonger.core;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders a FEN as a phone screenshot for DetectorCore /
 * Tracker tests, mirroring the Python livetest harness
 * (~/workspace/livetest/harness.py) in Java, so tests run
 * on the JVM with no Android and no external files.
 *
 * Phone: 945x2048, dark UI, board at (0,608,945,1553) —
 * the same rect as the user's phone screenshots.
 * Pieces are centred shapes (pawn = small circle, rook =
 * rectangle, knight = triangle, others = larger circle),
 * white with a dark outline, black with a light outline.
 * The app only classifies occupancy colour, not piece type,
 * so shape variety is for centre-sample realism, not type.
 * Last-move squares are tinted toward yellow at 45%, like
 * Chess.com / Lichess.
 */
public final class TestBoardRenderer {

    public static final Rect BOARD = new Rect(0, 608, 945, 1553);

    public static final class Theme {
        public final int[] light, dark, whitePiece, blackPiece;
        public Theme(int[] light, int[] dark, int[] whitePiece, int[] blackPiece) {
            this.light = light; this.dark = dark;
            this.whitePiece = whitePiece; this.blackPiece = blackPiece;
        }
    }

    public static final Map<String, Theme> THEMES = new HashMap<>();
    static {
        THEMES.put("chesscom_green", new Theme(
                new int[]{191,236,119}, new int[]{146,191,93},
                new int[]{245,245,245}, new int[]{45,45,45}));
        THEMES.put("chesscom_wood", new Theme(
                new int[]{200,165,110}, new int[]{131,91,54},
                new int[]{245,240,230}, new int[]{50,50,50}));
        THEMES.put("lichess_brown", new Theme(
                new int[]{240,217,181}, new int[]{181,136,99},
                new int[]{250,250,250}, new int[]{40,40,40}));
        THEMES.put("lichess_blue", new Theme(
                new int[]{222,227,230}, new int[]{140,162,173},
                new int[]{250,250,250}, new int[]{40,40,40}));
        THEMES.put("lichess_purple", new Theme(
                new int[]{220,210,230}, new int[]{140,110,170},
                new int[]{250,250,250}, new int[]{40,40,40}));
        THEMES.put("dark_grey", new Theme(
                new int[]{180,180,180}, new int[]{90,90,90},
                new int[]{235,235,235}, new int[]{30,30,30}));
        THEMES.put("beige_white", new Theme(
                new int[]{220,200,170}, new int[]{160,120,80},
                new int[]{225,205,175}, new int[]{55,55,55}));
        THEMES.put("white_on_white", new Theme(
                new int[]{250,250,240}, new int[]{120,120,120},
                new int[]{245,245,245}, new int[]{40,40,40}));
        THEMES.put("black_on_black", new Theme(
                new int[]{230,230,230}, new int[]{42,42,42},
                new int[]{250,250,250}, new int[]{46,46,46}));
        THEMES.put("low_contrast", new Theme(
                new int[]{210,210,210}, new int[]{190,190,190},
                new int[]{245,245,245}, new int[]{45,45,45}));
    }

    private static int sq(String s) {
        return (s.charAt(1) - '1') * 8 + (s.charAt(0) - 'a');
    }

    /** FEN placement -> sq -> [colour, kind]. */
    public static Map<Integer, int[]> piecesOf(String fen) {
        Map<Integer, int[]> out = new HashMap<>();
        String placement = fen.split(" ")[0];
        int rank = 7, file = 0;
        for (char ch : placement.toCharArray()) {
            if (ch == '/') { rank--; file = 0; }
            else if (Character.isDigit(ch)) { file += ch - '0'; }
            else {
                int sq = rank * 8 + file;
                int colour = Character.isUpperCase(ch)
                        ? DetectorCore.WHITE_PIECE : DetectorCore.BLACK_PIECE;
                out.put(sq, new int[]{colour, Character.toUpperCase(ch)});
                file++;
            }
        }
        return out;
    }

    public static int[] occupancyOf(String fen) {
        int[] occ = new int[64];
        for (Map.Entry<Integer, int[]> e : piecesOf(fen).entrySet()) {
            occ[e.getKey()] = e.getValue()[0];
        }
        return occ;
    }

    /** Render a FEN. lastMoveUci may be null (no highlight). */
    public static BufferedImage render(String fen, Theme theme, String lastMoveUci) {
        BufferedImage im = new BufferedImage(945, 2048, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setColor(new Color(30, 30, 30));
        g.fillRect(0, 0, 945, 2048);
        int hlFrom = -1, hlTo = -1;
        if (lastMoveUci != null && lastMoveUci.length() >= 4) {
            hlFrom = sq(lastMoveUci.substring(0, 2));
            hlTo = sq(lastMoveUci.substring(2, 4));
        }
        Map<Integer, int[]> pieces = piecesOf(fen);
        for (int sq = 0; sq < 64; sq++) {
            Rect cell = DetectorCore.squareRect(BOARD, sq, true);
            int file = sq % 8, rank = sq / 8;
            int col = file, row = 7 - rank;
            int[] base = (row + col) % 2 == 0 ? theme.light : theme.dark;
            if (sq == hlFrom || sq == hlTo) {
                base = new int[]{
                        (int) (base[0] * 0.55 + 255 * 0.45),
                        (int) (base[1] * 0.55 + 255 * 0.45),
                        (int) (base[2] * 0.55 + 0 * 0.45)};
            }
            g.setColor(new Color(base[0], base[1], base[2]));
            g.fillRect(cell.left, cell.top, cell.width(), cell.height());
        }
        for (Map.Entry<Integer, int[]> e : pieces.entrySet()) {
            int sq = e.getKey();
            int colour = e.getValue()[0];
            char kind = (char) e.getValue()[1];
            Rect cell = DetectorCore.squareRect(BOARD, sq, true);
            int cx = (cell.left + cell.right) / 2;
            int cy = (cell.top + cell.bottom) / 2;
            int w = cell.width(), h = cell.height();
            int[] fill = colour == DetectorCore.WHITE_PIECE ? theme.whitePiece : theme.blackPiece;
            Color fillC = new Color(fill[0], fill[1], fill[2]);
            Color outlineC = colour == DetectorCore.WHITE_PIECE
                    ? new Color(30, 30, 30) : new Color(220, 220, 220);
            g.setColor(fillC);
            if (kind == 'P') {
                int r = (int) (w * 0.28);
                g.fillOval(cx - r, cy - r, r * 2, r * 2);
                g.setColor(outlineC);
                g.drawOval(cx - r, cy - r, r * 2, r * 2);
            } else if (kind == 'R') {
                g.fillRect((int) (cx - w * 0.30), (int) (cy - h * 0.30),
                        (int) (w * 0.60), (int) (h * 0.60));
                g.setColor(outlineC);
                g.drawRect((int) (cx - w * 0.30), (int) (cy - h * 0.30),
                        (int) (w * 0.60), (int) (h * 0.60));
            } else if (kind == 'N') {
                Polygon p = new Polygon();
                p.addPoint(cx, (int) (cy - h * 0.32));
                p.addPoint((int) (cx + w * 0.30), (int) (cy + h * 0.30));
                p.addPoint((int) (cx - w * 0.30), (int) (cy + h * 0.30));
                g.fillPolygon(p);
                g.setColor(outlineC);
                g.drawPolygon(p);
            } else {
                int r = (int) (w * 0.34);
                g.fillOval(cx - r, cy - r, r * 2, r * 2);
                g.setColor(outlineC);
                g.drawOval(cx - r, cy - r, r * 2, r * 2);
            }
        }
        g.dispose();
        return im;
    }

    /** BufferedImage -> ARGB int[] pixels, as Android's
     *  Bitmap.getPixels produces (0xff000000 | r<<16 | g<<8 | b). */
    public static int[] pixels(BufferedImage im) {
        int w = im.getWidth(), h = im.getHeight();
        int[] px = new int[w * h];
        im.getRGB(0, 0, w, h, px, 0, w);
        return px;
    }
}
