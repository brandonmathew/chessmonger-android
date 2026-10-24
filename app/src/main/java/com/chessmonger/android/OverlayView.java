package com.chessmonger.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.View;

/**
 * The floating overlay: a full-screen, touch-transparent view that
 * draws on top of whatever chess app is in front.
 *
 * It draws (a) the detected board rectangle, (b) the suggested move
 * as a thick arrow from the from-square's centre to the to-square's
 * centre, with the two squares tinted, and (c) a one-line status
 * (move + eval + depth, or "waiting…"). It never intercepts touches:
 * the WindowManager params in CaptureService carry
 * FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE, so taps pass straight
 * through to the game underneath. This is suggestion-only — it
 * does not tap or play the move for you.
 */
public final class OverlayView extends View {

    private final Paint boardPaint = new Paint();
    private final Paint squarePaint = new Paint();
    private final Paint arrowPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint textBgPaint = new Paint();

    private Rect board;
    private int fromSq = -1, toSq = -1;
    private boolean whiteAtBottom = true;
    private String status = "ChessMonger: looking for board…";

    public OverlayView(Context context) {
        super(context);
        boardPaint.setStyle(Paint.Style.STROKE);
        boardPaint.setStrokeWidth(5);
        boardPaint.setColor(Color.parseColor("#00E676"));
        squarePaint.setStyle(Paint.Style.FILL);
        squarePaint.setColor(Color.argb(110, 0, 230, 118));
        arrowPaint.setStyle(Paint.Style.STROKE);
        arrowPaint.setStrokeWidth(14);
        arrowPaint.setStrokeCap(Paint.Cap.ROUND);
        arrowPaint.setColor(Color.parseColor("#00E676"));
        arrowPaint.setAntiAlias(true);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(44);
        textPaint.setAntiAlias(true);
        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setColor(Color.argb(200, 0, 0, 0));
    }

    public synchronized void setBoard(Rect board, boolean whiteAtBottom) {
        // Skip a redraw when nothing changed: with the board
        // locked, setBoard is called every tick with the same
        // rect, and redrawing the outline every 600 ms was part
        // of the visible flicker even when the rect was stable.
        Rect next = board == null ? null : new Rect(board);
        if (whiteAtBottom == this.whiteAtBottom
                && ((next == null && this.board == null)
                        || (next != null && next.equals(this.board)))) {
            return;
        }
        this.board = next;
        this.whiteAtBottom = whiteAtBottom;
        postInvalidate();
    }

    public synchronized void setSuggestion(Rect board, int fromSq, int toSq,
                                           boolean whiteAtBottom,
                                           String status) {
        Rect next = board == null ? null : new Rect(board);
        if (whiteAtBottom == this.whiteAtBottom
                && fromSq == this.fromSq && toSq == this.toSq
                && status != null && status.equals(this.status)
                && ((next == null && this.board == null)
                        || (next != null && next.equals(this.board)))) {
            return;
        }
        this.board = next;
        this.fromSq = fromSq;
        this.toSq = toSq;
        this.whiteAtBottom = whiteAtBottom;
        this.status = status;
        postInvalidate();
    }

    public synchronized void setStatus(String status) {
        this.status = status;
        postInvalidate();
    }

    public synchronized void clearSuggestion() {
        fromSq = -1;
        toSq = -1;
        postInvalidate();
    }

    @Override protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (board != null) {
            canvas.drawRect(board, boardPaint);
            if (fromSq >= 0) {
                Rect f = BoardDetector.squareRect(board, fromSq, whiteAtBottom);
                canvas.drawRect(f, squarePaint);
            }
            if (toSq >= 0) {
                Rect t = BoardDetector.squareRect(board, toSq, whiteAtBottom);
                canvas.drawRect(t, squarePaint);
            }
            if (fromSq >= 0 && toSq >= 0) {
                Rect f = BoardDetector.squareRect(board, fromSq, whiteAtBottom);
                Rect t = BoardDetector.squareRect(board, toSq, whiteAtBottom);
                float x1 = f.centerX(), y1 = f.centerY();
                float x2 = t.centerX(), y2 = t.centerY();
                // Shorten the shaft so the arrowhead lands in the target
                // square instead of poking past it.
                float dx = x2 - x1, dy = y2 - y1;
                float len = (float) Math.hypot(dx, dy);
                if (len > 1) {
                    float cut = Math.min(len * 0.28f, 60);
                    x2 -= dx / len * cut;
                    y2 -= dy / len * cut;
                }
                canvas.drawLine(x1, y1, x2, y2, arrowPaint);
                // Arrowhead.
                float angle = (float) Math.atan2(y2 - y1, x2 - x1);
                float head = 42;
                Path p = new Path();
                p.moveTo(x2, y2);
                p.lineTo((float) (x2 - head * Math.cos(angle - 0.45)),
                        (float) (y2 - head * Math.sin(angle - 0.45)));
                p.lineTo((float) (x2 - head * Math.cos(angle + 0.45)),
                        (float) (y2 - head * Math.sin(angle + 0.45)));
                p.close();
                Paint fill = new Paint(arrowPaint);
                fill.setStyle(Paint.Style.FILL);
                canvas.drawPath(p, fill);
            }
        }
        if (status != null && !status.isEmpty()) {
            float w = textPaint.measureText(status) + 32;
            float top = 120;
            canvas.drawRect(24, top, 24 + w, top + 68, textBgPaint);
            canvas.drawText(status, 40, top + 48, textPaint);
        }
    }
}
