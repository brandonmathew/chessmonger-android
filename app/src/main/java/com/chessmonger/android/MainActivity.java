package com.chessmonger.android;

import com.chessmonger.core.ChessBoard;
import com.chessmonger.core.TrackerLog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Setup screen. Flow:
 *  1. Grant "draw over other apps" (the overlay arrow needs it).
 *  2. Pick your colour / think time.
 *  3. Start -> allow screen recording, then switch to your
 *     chess app; the green arrow appears on top of the board
 *     when it is your turn.
 *  4. Stop here removes the overlay and capture.
 *
 * API notes (26-37, Android 8-17): overlay uses
 * TYPE_APPLICATION_OVERLAY (introduced in API 26, so it is the
 * only path on this range); the foreground service declares
 * mediaProjection type and is started with startForegroundService
 * on API 26+ (plain startService would be rejected from the
 * background on API 31+); notifications need a runtime grant on
 * API 33+.
 *
 * Suggestion-only: the app draws the move, it never taps for you.
 */
public final class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_OVERLAY = 1002;
    private static final int REQ_NOTIFICATIONS = 1003;

    private RadioGroup colorGroup;
    private EditText thinkEdit;
    private EditText fenEdit;
    private TextView statusText;
    private TextView permissionText;
    private boolean crashPromptShown;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ChessMonger Android");
        title.setTextSize(26);
        root.addView(title);

        TextView how = new TextView(this);
        how.setText("Screen-vision Stockfish suggestions, drawn on top of "
                + "any chess app.\n\n1. Allow drawing over other apps.\n"
                + "2. Pick your colour and think time.\n"
                + "3. Start, allow screen recording, then switch to "
                + "your chess app.\n"
                + "The green arrow shows Stockfish's best move when "
                + "it is your turn. It never taps for you.\n\n"
                + "Board detection: the app locks onto the 8x8 board "
                + "once found and does not re-detect it, so the "
                + "outline and arrow do not resize or glitch. It "
                + "then tracks moves from last-move highlights first "
                + "and square occupancy second, and a move confirms "
                + "in under a second (2-3 stable frames at a fast "
                + "tick), so animations and drags cannot throw it "
                + "out of sync. If it does lose "
                + "track, it now tries to recover on its own first "
                + "— re-checking the position, rolling back an "
                + "undo, or finding the missed move(s) — and only "
                + "asks for a FEN if that fails. Resync still "
                + "forces a fresh re-read if you want it.");
        root.addView(how);

        permissionText = new TextView(this);
        root.addView(permissionText);

        Button overlayBtn = new Button(this);
        overlayBtn.setText("1. Allow overlay over other apps");
        overlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestOverlay(); }
        });
        root.addView(overlayBtn);

        TextView colorLabel = new TextView(this);
        colorLabel.setText("I play as:");
        root.addView(colorLabel);
        colorGroup = new RadioGroup(this);
        RadioButton whiteBtn = new RadioButton(this);
        whiteBtn.setText("White (board white at bottom)");
        whiteBtn.setId(1);
        RadioButton blackBtn = new RadioButton(this);
        blackBtn.setText("Black (board flipped)");
        blackBtn.setId(2);
        RadioButton eitherBtn = new RadioButton(this);
        eitherBtn.setText("Either side (suggest for side to move)");
        eitherBtn.setId(3);
        colorGroup.addView(whiteBtn);
        colorGroup.addView(blackBtn);
        colorGroup.addView(eitherBtn);
        colorGroup.check(1);
        root.addView(colorGroup);

        TextView thinkLabel = new TextView(this);
        thinkLabel.setText("Stockfish think time (milliseconds, 500-10000):");
        root.addView(thinkLabel);
        thinkEdit = new EditText(this);
        thinkEdit.setText("2000");
        thinkEdit.setInputType(
                android.text.InputType.TYPE_CLASS_NUMBER);
        root.addView(thinkEdit);

        TextView fenLabel = new TextView(this);
        fenLabel.setText("Join mid-game? Paste a FEN (optional, leave "
                + "blank to start from the opening position):");
        root.addView(fenLabel);
        fenEdit = new EditText(this);
        fenEdit.setHint("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -");
        root.addView(fenEdit);

        Button startBtn = new Button(this);
        startBtn.setText("2. Start watching the board");
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startWatching(); }
        });
        root.addView(startBtn);

        Button resyncBtn = new Button(this);
        resyncBtn.setText("Resync (re-read the current board)");
        resyncBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this,
                        CaptureService.class);
                i.setAction(CaptureService.ACTION_RESYNC);
                startService(i);
                toast("Resync requested");
            }
        });
        root.addView(resyncBtn);

        Button fenBtn = new Button(this);
        fenBtn.setText("Apply FEN above");
        fenBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this,
                        CaptureService.class);
                i.setAction(CaptureService.ACTION_SET_FEN);
                i.putExtra(CaptureService.EXTRA_FEN,
                        fenEdit.getText().toString().trim());
                startService(i);
                toast("FEN sent to the tracker");
            }
        });
        root.addView(fenBtn);

        Button stopBtn = new Button(this);
        stopBtn.setText("Stop");
        stopBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(MainActivity.this,
                        CaptureService.class);
                i.setAction(CaptureService.ACTION_STOP);
                startService(i);
                toast("Stopped");
            }
        });
        root.addView(stopBtn);

        statusText = new TextView(this);
        statusText.setText("Not running. Stockfish 17.1, max strength: "
                + "no skill cap, no Elo limit, all cores, hash scaled to device RAM. "
                + "Longer think time = deeper search.");
        root.addView(statusText);

        Button crashBtn = new Button(this);
        crashBtn.setText("Show last crash report (if any)");
        crashBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                crashPromptShown = false;
                showCrashReportIfAny();
                if (!crashPromptShown) {
                    toast("No crash report saved");
                }
            }
        });
        root.addView(crashBtn);

        Button updateBtn = new Button(this);
        updateBtn.setText("Check for updates");
        updateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                UpdateChecker.check(MainActivity.this, true);
            }
        });
        root.addView(updateBtn);

        Button logBtn = new Button(this);
        logBtn.setText("Share tracker log (if running)");
        logBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                TrackerLog log = CaptureService.trackerLog();
                if (log == null || log.snapshot().isEmpty()) {
                    toast("No tracker log yet — start watching first");
                    return;
                }
                final String text = log.toString();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Tracker log")
                        .setMessage("Last " + log.snapshot().size()
                                + " tracker events. Share it to Kai "
                                + "with a screenshot if tracking froze.")
                        .setPositiveButton("Share", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                Intent share = new Intent(Intent.ACTION_SEND);
                                share.setType("text/plain");
                                share.putExtra(Intent.EXTRA_TEXT, text);
                                startActivity(Intent.createChooser(share, "Share tracker log"));
                            }
                        })
                        .setNeutralButton("Copy", new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface d, int w) {
                                ClipboardManager cm = (ClipboardManager)
                                        getSystemService(Context.CLIPBOARD_SERVICE);
                                cm.setPrimaryClip(ClipData.newPlainText("tracker log", text));
                                toast("Tracker log copied");
                            }
                        })
                        .setNegativeButton("Dismiss", null)
                        .show();
            }
        });
        root.addView(logBtn);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        requestNotificationsIfNeeded();
        showCrashReportIfAny();
        // v1.9.0: update check on every FRESH start only.
        // onCreate runs once per app launch, not on every resume
        // from the chess app, so this does not re-check (and
        // re-prompt) every time the user switches back here.
        // Failures are silent here (repo may not exist yet, or
        // the phone may be offline); the manual button above
        // reports a failure with a toast instead.
        UpdateChecker.check(this, false);
    }

    /**
     * If the previous run crashed, CrashHandler saved a report.
     * Offer it: share it (e.g. paste it to Kai), copy it, or
     * dismiss it. Share/Copy/Dismiss all delete the file, so it
     * does not re-prompt on every launch.
     */
    private void showCrashReportIfAny() {
        if (crashPromptShown) return;
        final java.io.File report = CrashHandler.latestReport(this);
        if (report == null) return;
        crashPromptShown = true;
        final String text = CrashHandler.readReport(report);
        String summary = text;
        int traceAt = text.indexOf("\nException:");
        if (traceAt > 0) summary = text.substring(0, traceAt);
        // v1.5.0: cap the dialog text. A report without the
        // expected marker used to dump its full (potentially
        // very long) text into the dialog.
        if (summary.length() > 2000) {
            summary = summary.substring(0, 2000) + "…";
        }
        new AlertDialog.Builder(this)
                .setTitle("The app crashed last time")
                .setMessage(summary
                        + "\nThe full report has the exact error and "
                        + "where it happened. Sharing it makes the bug "
                        + "fixable — send it to Kai and it can be pinned "
                        + "down precisely.")
                .setPositiveButton("Share report",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface dialog, int which) {
                                Intent share = new Intent(
                                        Intent.ACTION_SEND);
                                share.setType("text/plain");
                                share.putExtra(Intent.EXTRA_SUBJECT,
                                        "ChessMonger crash report");
                                share.putExtra(Intent.EXTRA_TEXT, text);
                                startActivity(Intent.createChooser(share,
                                        "Share crash report"));
                                CrashHandler.deleteReport(report);
                            }
                        })
                .setNeutralButton("Copy",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface dialog, int which) {
                                ClipboardManager clipboard =
                                        (ClipboardManager) getSystemService(
                                                Context.CLIPBOARD_SERVICE);
                                if (clipboard != null) {
                                    clipboard.setPrimaryClip(
                                            ClipData.newPlainText(
                                                    "ChessMonger crash report",
                                                    text));
                                }
                                CrashHandler.deleteReport(report);
                            }
                        })
                .setNegativeButton("Dismiss",
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(
                                    DialogInterface dialog, int which) {
                                CrashHandler.deleteReport(report);
                            }
                        })
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshPermissionText();
    }

    /** One line per permission, re-checked on every resume because
     * Android (and OEM battery tools) can revoke the overlay
     * grant behind the app's back. */
    private void refreshPermissionText() {
        if (permissionText == null) return;
        String overlay = Settings.canDrawOverlays(this)
                ? "allowed" : "NOT allowed";
        String notif;
        if (Build.VERSION.SDK_INT < 33) {
            notif = "n/a below Android 13";
        } else {
            notif = checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED
                    ? "allowed" : "NOT allowed";
        }
        permissionText.setText("Permissions — Overlay: " + overlay
                + " · Notifications: " + notif
                + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
    }

    /** API 33+ only: POST_NOTIFICATIONS is a runtime permission.
     * The foreground-service notification still posts without it,
     * but asking keeps the status line honest and the service
     * visible on Android 13-16. */
    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(
                        android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{
                            android.Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions,
                grantResults);
        refreshPermissionText();
    }

    /** API 26+: a foreground service must be started with
     * startForegroundService, not startService. On API 31+ a
     * background start is rejected with IllegalStateException —
     * that used to crash the setup screen, now it is a toast. */
    private void startForeground(Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(i);
            } else {
                startService(i);
            }
        } catch (Exception e) {
            toast("Android blocked starting the service — reopen "
                    + "the app and try again (" + e.getClass().getSimpleName()
                    + ")");
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void requestOverlay() {
        if (Settings.canDrawOverlays(this)) {
            toast("Overlay already allowed");
            return;
        }
        Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(i, REQ_OVERLAY);
    }

    private void startWatching() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Allow overlay first (button 1)");
            requestOverlay();
            return;
        }
        MediaProjectionManager mgr = (MediaProjectionManager)
                getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mgr == null) {
            toast("This device does not offer screen recording");
            return;
        }
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    private void startCaptureService(int resultCode, Intent data) {
        int myColor = 1;
        int checked = colorGroup.getCheckedRadioButtonId();
        if (checked == 2) myColor = -1;
        if (checked == 3) myColor = 0;
        int thinkMs = 2000;
        try {
            thinkMs = Integer.parseInt(
                    thinkEdit.getText().toString().trim());
        } catch (Exception ignored) {}
        // v1.5.0: validate a pasted FEN here, in the UI, instead
        // of letting the service silently ignore a bad one and
        // start from the opening position with no explanation.
        String fen = fenEdit.getText().toString().trim();
        if (!fen.isEmpty()) {
            ChessBoard test = new ChessBoard();
            if (!test.setFen(fen)) {
                toast("Invalid FEN — starting from the opening position");
                fen = "";
            }
        }
        Intent i = new Intent(this, CaptureService.class);
        i.setAction(CaptureService.ACTION_START);
        i.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        i.putExtra(CaptureService.EXTRA_DATA, data);
        i.putExtra(CaptureService.EXTRA_MY_COLOR, myColor);
        i.putExtra(CaptureService.EXTRA_THINK_MS, thinkMs);
        i.putExtra(CaptureService.EXTRA_FEN, fen);
        startForeground(i);
        statusText.setText("Running — switch to your chess app. "
                + "The overlay arrow appears on your turn.");
        toast("Running — switch to your chess app");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode,
                                              Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            toast(Settings.canDrawOverlays(this)
                    ? "Overlay allowed" : "Overlay still not allowed");
            return;
        }
        if (requestCode == REQ_CAPTURE) {
            if (resultCode != RESULT_OK || data == null) {
                toast("Screen recording was not allowed — cannot see the board");
                return;
            }
            startCaptureService(resultCode, data);
        }
    }
}
