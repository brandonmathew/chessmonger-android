package com.chessmonger.core;

/**
 * Minimal rectangle, mirroring android.graphics.Rect's API
 * (left/top/right/bottom fields, width()/height(), equals)
 * so DetectorCore and Tracker never need Android. The Android
 * shell converts between this and android.graphics.Rect at
 * the boundary, in exactly two places (CaptureService).
 */
public final class Rect {
    public int left, top, right, bottom;

    public Rect() {}

    public Rect(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public Rect(Rect other) {
        this.left = other.left;
        this.top = other.top;
        this.right = other.right;
        this.bottom = other.bottom;
    }

    public int width() { return right - left; }
    public int height() { return bottom - top; }

    @Override public boolean equals(Object o) {
        if (!(o instanceof Rect)) return false;
        Rect r = (Rect) o;
        return left == r.left && top == r.top
                && right == r.right && bottom == r.bottom;
    }

    @Override public int hashCode() {
        return left * 31 * 31 * 31 + top * 31 * 31 + right * 31 + bottom;
    }

    @Override public String toString() {
        return "[" + left + "," + top + "," + right + "," + bottom + "]";
    }
}
