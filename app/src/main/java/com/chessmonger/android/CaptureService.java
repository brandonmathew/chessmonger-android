package com.chessmonger.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.chessmonger.core.ChessBoard;
import com.chessmonger.core.DetectorCore;
import com.chessmonger.core.Tracker;
import com.chessmonger.core.TrackerLog;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service, v2.0.0: this file is now ONLY the Android
 * shell — screen capture (MediaProjection), the overlay, and
 * Stockfish execution. All board tracking lives in core.Tracker,
 * which is pure Java and tested on the JVM (TrackerTest).
 *
 * It never taps the screen and never sends input to the game.
 * It only watches and draws the suggestion arrow on top.
 *
 * Control it with startService intents (same actions as v1):
 *   ACTION_START  + resultCode/data, myColor, thinkMs, fen
 *   ACTION_STOP, ACTION_RESYNC, ACTION_SET_FEN + fen
 *
 * Capture details that must stay here (they are Android-only
 * and were hard-won in v1, so they are copied, not reinvented):
 *  - MediaProjection.Callback registered BEFORE
 *    createVirtualDisplay, or API 34+ throws IllegalStateException.
 *  - Previous capture fully stopped before a new one, or API 34+
 *    throws SecurityException on the next createVirtualDisplay.
 *  - Projection callback unregistered BEFORE stop(), so our own
 *    stop does not re-enter stopAll via onStop().
 *  - A restart with no intent stops cleanly (START_NOT_STICKY):
 *    a MediaProjection grant is dead with the old process, so
 *    there is no zombie service to recover.
 */
public final class CaptureService extends Service {

    public static final String ACTION_START = "com.chessmonger.android.START";
    public static final String ACTION_STOP = "com.chessmonger.android.STOP";
    public static final String ACTION_RESYNC = "com.chessmonger.android.RESYNC";
    public static final String ACTION_SET_FEN = "com.chessmonger.android.SET_FEN";

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_MY_COLOR = "myColor";
    public static final String EXTRA_THINK_MS = "thinkMs";
    public static final String EXTRA_FEN = "fen";

    private static final String CHANNEL = "chessmonger";
    private static final int NOTIF_ID = 1;
    private static final long TICK_MS = 250;

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private VirtualDisplay.Callback virtualDisplayCallback;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private WindowManager windowManager;
    private OverlayView overlay;
    private ExecutorService engineThread;

    private Tracker tracker;
    private int thinkMs = 2000;
    private int captureWidth, captureHeight;
    private String lastStatus = "";

    private static volatile boolean sRunning;
    /** Last tracker log, for MainActivity's Share button.
     *  Same process as the activity, so a static reference to
     *  the live log object is safe here (it is updated in
     *  place by Tracker, not replaced). */
    private static volatile TrackerLog sTrackerLog;

    public static boolean isRunning() { return sRunning; }
    public static TrackerLog trackerLog() { return sTrackerLog; }

    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            try { captureTick(); } catch (Exception ignored) {}
            if (captureHandler != null) {
                captureHandler.postDelayed(this, TICK_MS);
            }
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (!ACTION_START.equals(action) && !sRunning) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopAll();
            return START_NOT_STICKY;
        }
        if (tracker == null) tracker = new Tracker(1);
        if (ACTION_RESYNC.equals(action)) {
            tracker.resync();
            if (overlay != null) {
                overlay.clearSuggestion();
                overlay.setBoard(null, tracker.whiteAtBottom());
            }
            setStatus("Resynced — re-reading board…");
            return START_STICKY;
        }
        if (ACTION_SET_FEN.equals(action)) {
            String fen = intent.getStringExtra(EXTRA_FEN);
            if (fen != null && tracker.setFen(fen)) {
                if (overlay != null) {
                    overlay.clearSuggestion();
                    overlay.setBoard(null, tracker.whiteAtBottom());
                }
                setStatus("Synced to FEN — re-reading board…");
            } else {
                setStatus("Invalid FEN — keeping previous position");
            }
            return START_STICKY;
        }
        if (ACTION_START.equals(action)) {
            int myColor = intent.getIntExtra(EXTRA_MY_COLOR, 1);
            thinkMs = intent.getIntExtra(EXTRA_THINK_MS, 2000);
            if (thinkMs < 500) thinkMs = 500;
            if (thinkMs > 10000) thinkMs = 10000;
            tracker = new Tracker(myColor);
            sTrackerLog = tracker.log();
            String fen = intent.getStringExtra(EXTRA_FEN);
            tracker.start(fen);
            sRunning = true;
            CrashHandler.setTrackerContext("starting, mode=MediaProjection, myColor="
                    + myColor + ", thinkMs=" + thinkMs);
            if (!setupForeground(intent)) {
                sRunning = false;
                stopSelf();
                return START_NOT_STICKY;
            }
            startCapture(intent);
            return START_STICKY;
        }
        return START_STICKY;
    }

    private boolean setupForeground(Intent intent) {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    "ChessMonger", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("ChessMonger is watching the board")
                .setContentText("Tap the app to stop. Overlay draws the suggestion.")
                .setSmallIcon(android.R.drawable.ic_menu_info_details);
        Notification notif = b.build();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, notif,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (Exception e) {
            return false;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return false;
        overlay = new OverlayView(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams
                            .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            windowManager.addView(overlay, params);
        } catch (Exception e) {
            overlay = null;
            try { stopForeground(true); } catch (Exception ignored) {}
            return false;
        }
        engineThread = Executors.newSingleThreadExecutor();
        return true;
    }

    private void startCapture(Intent intent) {
        stopCaptureOnly();
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_DATA, Intent.class);
        } else {
            data = intent.getParcelableExtra(EXTRA_DATA);
        }
        MediaProjectionManager mgr = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null || data == null) {
            setStatus("No screen-capture grant — restart from the app.");
            return;
        }
        try {
            projection = mgr.getMediaProjection(resultCode, data);
        } catch (Exception e) {
            setStatus("Screen-recording grant was lost ("
                    + e.getClass().getSimpleName()
                    + ") — restart watching from the app and allow it again");
            return;
        }
        if (projection == null) {
            setStatus("No screen-capture grant — restart from the app.");
            return;
        }
        projectionCallback = new MediaProjection.Callback() {
            @Override public void onStop() { stopAll(); }
        };
        try {
            projection.registerCallback(projectionCallback, null);
        } catch (Exception e) {
            setStatus("Could not start screen capture ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + ") — restart watching from the app");
            return;
        }
        virtualDisplayCallback = new VirtualDisplay.Callback() {
            @Override public void onPaused() {}
            @Override public void onResumed() {}
            @Override public void onStopped() {}
        };
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int w = metrics.widthPixels, h = metrics.heightPixels;
        captureWidth = w;
        captureHeight = h;
        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        try {
            virtualDisplay = projection.createVirtualDisplay("ChessMonger",
                    w, h, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), virtualDisplayCallback, null);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : ": " + e.getMessage();
            setStatus("Could not start screen capture ("
                    + e.getClass().getSimpleName() + msg
                    + ") — restart watching from the app");
            return;
        }
        if (virtualDisplay == null) {
            setStatus("Could not start screen capture — restart watching from the app");
            return;
        }
        captureThread = new HandlerThread("capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        captureHandler.post(tickRunnable);
        setStatus("ChessMonger: looking for board…");
    }

    private void captureTick() {
        if (imageReader == null || tracker == null) return;
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
        } catch (Exception ignored) { return; }
        if (image == null) return;
        Bitmap bmp;
        try {
            bmp = imageToBitmap(image);
        } finally {
            image.close();
        }
        if (bmp == null) return;
        try {
            DisplayMetrics m = getResources().getDisplayMetrics();
            if (captureWidth > 0 && captureHeight > 0
                    && (m.widthPixels != captureWidth
                            || m.heightPixels != captureHeight)) {
                captureWidth = m.widthPixels;
                captureHeight = m.heightPixels;
                if (tracker.lockedBoard() != null) {
                    tracker.clearForSizeChange();
                    setStatus("Screen size changed — re-locking board…");
                }
            }
        } catch (Exception ignored) {
        }
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            Tracker.Result r = tracker.processPixels(px, w, h);
            handleResult(r);
        } finally {
            bmp.recycle();
        }
    }

    private void handleResult(Tracker.Result r) {
        if (r == null) return;
        if (r.clearBoard && overlay != null) {
            overlay.clearSuggestion();
            overlay.setBoard(null, r.whiteAtBottom);
        }
        if (r.boardRect != null && overlay != null) {
            overlay.setBoard(toAndroid(r.boardRect), r.whiteAtBottom);
        }
        if (r.clearSuggestion && overlay != null) {
            overlay.clearSuggestion();
        }
        if (r.status != null) setStatus(r.status);
        if (r.analyseFen != null) {
            analyse(r.analyseFen);
        }
    }

    private void analyse(final String fenForEngine) {
        setStatus("Thinking…");
        final com.chessmonger.core.Rect boardRectCore = tracker.lockedBoard();
        if (boardRectCore == null) {
            tracker.clearAnalysingFenIfEquals(fenForEngine);
            return;
        }
        final Rect boardRect = toAndroid(boardRectCore);
        if (engineThread == null) return;
        engineThread.execute(new Runnable() {
            @Override public void run() {
                StockfishEngine engine = StockfishEngine.get(CaptureService.this);
                if (engine == null) {
                    tracker.clearAnalysingFen();
                    setStatus("Stockfish failed to start on this phone");
                    return;
                }
                StockfishEngine.Result r = engine.analyze(fenForEngine, thinkMs);
                if (r == null || r.bestMove == null) {
                    setStatus("Stockfish gave no move — retrying next frame");
                    tracker.clearAnalysingFen();
                    return;
                }
                if (!fenForEngine.equals(tracker.fen())) {
                    tracker.clearAnalysingFenIfEquals(fenForEngine);
                    return;
                }
                ChessBoard.Move m = ChessBoard.moveFromUci(r.bestMove);
                if (m == null || overlay == null) return;
                com.chessmonger.core.Rect locked = tracker.lockedBoard();
                if (locked == null || !toAndroid(locked).equals(boardRect)) {
                    tracker.clearAnalysingFen();
                    return;
                }
                overlay.setSuggestion(boardRect, m.from, m.to,
                        tracker.whiteAtBottom(),
                        "Play " + r.bestMove + "  ·  Eval " + r.evalText()
                                + "  ·  Depth " + r.depth);
            }
        });
    }

    private static Rect toAndroid(com.chessmonger.core.Rect r) {
        return new Rect(r.left, r.top, r.right, r.bottom);
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        Bitmap bmp = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(), Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(buffer);
        if (rowPadding != 0) {
            Bitmap cropped = Bitmap.createBitmap(bmp, 0, 0,
                    image.getWidth(), image.getHeight());
            bmp.recycle();
            return cropped;
        }
        return bmp;
    }

    private synchronized void setStatus(final String s) {
        if (s.equals(lastStatus)) return;
        lastStatus = s;
        if (tracker != null) {
            CrashHandler.setTrackerContext("mode=MediaProjection"
                    + ", myColor=" + tracker.myColor() + ", thinkMs=" + thinkMs
                    + ", fen=" + tracker.fen() + ", status=" + s);
        }
        if (overlay != null) overlay.setStatus(s);
    }

    private void stopCaptureOnly() {
        if (captureHandler != null) {
            captureHandler.removeCallbacks(tickRunnable);
            captureHandler = null;
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        virtualDisplayCallback = null;
        if (projection != null) {
            if (projectionCallback != null) {
                try { projection.unregisterCallback(projectionCallback); }
                catch (Exception ignored) {}
                projectionCallback = null;
            }
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
            imageReader = null;
        }
    }

    private void stopAll() {
        sRunning = false;
        stopCaptureOnly();
        if (engineThread != null) {
            engineThread.shutdownNow();
            engineThread = null;
        }
        StockfishEngine.shutdown();
        if (windowManager != null && overlay != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) {}
            overlay = null;
        }
        try { stopForeground(true); } catch (Exception ignored) {}
        try { stopSelf(); } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        stopAll();
        super.onDestroy();
    }
}
