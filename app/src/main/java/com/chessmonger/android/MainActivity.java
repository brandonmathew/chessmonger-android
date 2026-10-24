package com.chessmonger.android;

import com.chessmonger.core.ChessBoard;
import com.chessmonger.core.EngineSettings;
import com.chessmonger.core.TrackerLog;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Setup screen, v2.1.0 recode — the old screen was a wall of
 * buttons and a long paragraph. This one is deliberately
 * minimal on first sight:
 *
 *  ChessMonger
 *  Stockfish suggestions, drawn on your board.
 *  [Permissions line — action only if overlay is missing]
 *  Play as: White | Black | Either
 *  [ Start watching ]   (becomes Stop when running)
 *  Status line
 *  Engine: Max strength · 2.0s   [Engine settings]
 *  [ More ]   (collapsed: FEN, Resync, tracker log, crash
 *              report, updates — tools you need rarely)
 *
 * Engine settings live in a dialog, not on the home screen,
 * and they persist (SharedPreferences) so you do not re-enter
 * them every launch — the old screen reset to White / 2000 ms
 * every time. Settings apply on the next Start (the engine
 * process is created with them); the dialog says so, so a
 * mid-game change cannot silently drop a search.
 *
 * API notes (26-37): overlay uses TYPE_APPLICATION_OVERLAY;
 * foreground service is started with startForegroundService
 * on API 26+; POST_NOTIFICATIONS is runtime on API 33+.
 * Suggestion-only: the app draws the move, it never taps.
 */
public final class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_OVERLAY = 1002;
    private static final int REQ_NOTIFICATIONS = 1003;
    private static final String PREFS = "chessmonger_settings";

    private RadioGroup colorGroup;
    private EditText fenEdit;
    private TextView statusText;
    private TextView permissionText;
    private TextView engineSummaryText;
    private Button overlayBtn;
    private Button startBtn;
    private Button moreBtn;
    private LinearLayout advancedContainer;
    private boolean crashPromptShown;

    private EngineSettings engineSettings = new EngineSettings();
    private int myColorPref = 1;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadSettings();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("ChessMonger");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Stockfish suggestions, drawn on your board. "
                + "It never taps for you.");
        subtitle.setTextColor(Color.GRAY);
        addWithMargin(root, subtitle, 0, 4);

        permissionText = new TextView(this);
        addWithMargin(root, permissionText, 16, 4);

        overlayBtn = new Button(this);
        overlayBtn.setText("Allow overlay over other apps");
        overlayBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestOverlay(); }
        });
        root.addView(overlayBtn);

        TextView colorLabel = sectionLabel("Play as");
        addWithMargin(root, colorLabel, 20, 4);
        colorGroup = new RadioGroup(this);
        colorGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton whiteBtn = new RadioButton(this);
        whiteBtn.setText("White");
        whiteBtn.setId(1);
        RadioButton blackBtn = new RadioButton(this);
        blackBtn.setText("Black");
        blackBtn.setId(2);
        RadioButton eitherBtn = new RadioButton(this);
        eitherBtn.setText("Either");
        eitherBtn.setId(3);
        colorGroup.addView(whiteBtn);
        colorGroup.addView(blackBtn);
        colorGroup.addView(eitherBtn);
        colorGroup.check(myColorPref == -1 ? 2 : (myColorPref == 0 ? 3 : 1));
        colorGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(RadioGroup g, int id) {
                myColorPref = id == 2 ? -1 : (id == 3 ? 0 : 1);
                saveSettings();
            }
        });
        root.addView(colorGroup);

        startBtn = new Button(this);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (CaptureService.isRunning()) {
                    Intent i = new Intent(MainActivity.this, CaptureService.class);
                    i.setAction(CaptureService.ACTION_STOP);
                    startService(i);
                    toast("Stopped");
                    updateStartButton();
                } else {
                    startWatching();
                }
            }
        });
        addWithMargin(root, startBtn, 20, 4);
        updateStartButton();

        statusText = new TextView(this);
        statusText.setText("Not running.");
        statusText.setTextColor(Color.GRAY);
        addWithMargin(root, statusText, 4, 4);

        TextView engineLabel = sectionLabel("Engine");
        addWithMargin(root, engineLabel, 20, 4);
        engineSummaryText = new TextView(this);
        engineSummaryText.setText(engineSettings.summary());
        root.addView(engineSummaryText);

        Button engineBtn = new Button(this);
        engineBtn.setText("Engine settings");
        engineBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showEngineSettingsDialog(); }
        });
        addWithMargin(root, engineBtn, 4, 4);

        moreBtn = new Button(this);
        moreBtn.setText("More");
        moreBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = advancedContainer.getVisibility() != View.VISIBLE;
                advancedContainer.setVisibility(show ? View.VISIBLE : View.GONE);
                moreBtn.setText(show ? "Show less" : "More");
            }
        });
        addWithMargin(root, moreBtn, 12, 4);

        advancedContainer = new LinearLayout(this);
        advancedContainer.setOrientation(LinearLayout.VERTICAL);
        advancedContainer.setVisibility(View.GONE);

        TextView fenLabel = new TextView(this);
        fenLabel.setText("Join mid-game? Paste a FEN (optional):");
        advancedContainer.addView(fenLabel);
        fenEdit = new EditText(this);
        fenEdit.setHint("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -");
        advancedContainer.addView(fenEdit);

        Button fenBtn = new Button(this);
        fenBtn.setText("Apply FEN");
        fenBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!CaptureService.isRunning()) {
                    toast("Start watching first");
                    return;
                }
                Intent i = new Intent(MainActivity.this, CaptureService.class);
                i.setAction(CaptureService.ACTION_SET_FEN);
                i.putExtra(CaptureService.EXTRA_FEN,
                        fenEdit.getText().toString().trim());
                startService(i);
                toast("FEN sent to the tracker");
            }
        });
        advancedContainer.addView(fenBtn);

        Button resyncBtn = new Button(this);
        resyncBtn.setText("Resync (re-read the current board)");
        resyncBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!CaptureService.isRunning()) {
                    toast("Start watching first");
                    return;
                }
                Intent i = new Intent(MainActivity.this, CaptureService.class);
                i.setAction(CaptureService.ACTION_RESYNC);
                startService(i);
                toast("Resync requested");
            }
        });
        advancedContainer.addView(resyncBtn);

        Button logBtn = new Button(this);
        logBtn.setText("Share tracker log");
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
        advancedContainer.addView(logBtn);

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
        advancedContainer.addView(crashBtn);

        Button updateBtn = new Button(this);
        updateBtn.setText("Check for updates");
        updateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                UpdateChecker.check(MainActivity.this, true);
            }
        });
        advancedContainer.addView(updateBtn);

        root.addView(advancedContainer);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        requestNotificationsIfNeeded();
        showCrashReportIfAny();
        UpdateChecker.check(this, false);
    }

    private TextView sectionLabel(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void addWithMargin(LinearLayout root, View v, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(top);
        p.bottomMargin = dp(bottom);
        v.setLayoutParams(p);
        root.addView(v);
    }

    private void updateStartButton() {
        if (startBtn == null) return;
        startBtn.setText(CaptureService.isRunning()
                ? "Stop watching" : "Start watching");
    }

    // ---- settings persistence ----

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        myColorPref = p.getInt("myColor", 1);
        engineSettings.thinkMs = p.getInt("thinkMs", 2000);
        engineSettings.skillLevel = p.getInt("skill", 20);
        engineSettings.limitStrength = p.getBoolean("limit", false);
        engineSettings.elo = p.getInt("elo", 1800);
        engineSettings.multipv = p.getInt("multipv", 1);
        engineSettings.threadsAuto = p.getBoolean("threadsAuto", true);
        engineSettings.threads = p.getInt("threads", 4);
        engineSettings.hashAuto = p.getBoolean("hashAuto", true);
        engineSettings.hashMb = p.getInt("hashMb", 128);
        engineSettings.contempt = p.getInt("contempt", 0);
        engineSettings.clamp();
    }

    private void saveSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("myColor", myColorPref)
                .putInt("thinkMs", engineSettings.thinkMs)
                .putInt("skill", engineSettings.skillLevel)
                .putBoolean("limit", engineSettings.limitStrength)
                .putInt("elo", engineSettings.elo)
                .putInt("multipv", engineSettings.multipv)
                .putBoolean("threadsAuto", engineSettings.threadsAuto)
                .putInt("threads", engineSettings.threads)
                .putBoolean("hashAuto", engineSettings.hashAuto)
                .putInt("hashMb", engineSettings.hashMb)
                .putInt("contempt", engineSettings.contempt)
                .apply();
        if (engineSummaryText != null) {
            engineSummaryText.setText(engineSettings.summary());
        }
    }

    // ---- engine settings dialog ----

    private void showEngineSettingsDialog() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        form.setPadding(pad, pad, pad, pad);

        final EditText thinkEdit = numberField(form, "Think time (ms, 500–10000)",
                String.valueOf(engineSettings.thinkMs));
        final EditText skillEdit = numberField(form, "Skill level (0–20, 20 = strongest)",
                String.valueOf(engineSettings.skillLevel));
        final CheckBox limitBox = new CheckBox(this);
        limitBox.setText("Limit strength to an Elo level");
        limitBox.setChecked(engineSettings.limitStrength);
        form.addView(limitBox);
        final EditText eloEdit = numberField(form, "Elo (1320–3190, only if limited)",
                String.valueOf(engineSettings.elo));
        eloEdit.setEnabled(engineSettings.limitStrength);
        limitBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                eloEdit.setEnabled(checked);
            }
        });
        final EditText multipvEdit = numberField(form,
                "MultiPV (1–5; >1 also shows 2nd/3rd moves on the board)",
                String.valueOf(engineSettings.multipv));
        final CheckBox threadsAutoBox = new CheckBox(this);
        threadsAutoBox.setText("Auto threads (all cores, max 8)");
        threadsAutoBox.setChecked(engineSettings.threadsAuto);
        form.addView(threadsAutoBox);
        final EditText threadsEdit = numberField(form, "Threads (1–8, only if not auto)",
                String.valueOf(engineSettings.threads));
        threadsEdit.setEnabled(!engineSettings.threadsAuto);
        threadsAutoBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                threadsEdit.setEnabled(!checked);
            }
        });
        final CheckBox hashAutoBox = new CheckBox(this);
        hashAutoBox.setText("Auto hash (scaled to this phone's RAM)");
        hashAutoBox.setChecked(engineSettings.hashAuto);
        form.addView(hashAutoBox);
        final EditText hashEdit = numberField(form,
                "Hash (MB, 16–1024, only if not auto — too large can kill the engine on a small phone)",
                String.valueOf(engineSettings.hashMb));
        hashEdit.setEnabled(!engineSettings.hashAuto);
        hashAutoBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean checked) {
                hashEdit.setEnabled(!checked);
            }
        });
        final EditText contemptEdit = numberField(form,
                "Contempt (-100–100, 0 = neutral; positive prefers avoiding draws)",
                String.valueOf(engineSettings.contempt));

        TextView note = new TextView(this);
        note.setText("Saved settings apply on your next start. "
                + "If you are watching now, stop and start again to use them.");
        note.setTextColor(Color.GRAY);
        form.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        new AlertDialog.Builder(this)
                .setTitle("Engine settings")
                .setView(scroll)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        engineSettings.thinkMs = parseInt(thinkEdit, engineSettings.thinkMs);
                        engineSettings.skillLevel = parseInt(skillEdit, engineSettings.skillLevel);
                        engineSettings.limitStrength = limitBox.isChecked();
                        engineSettings.elo = parseInt(eloEdit, engineSettings.elo);
                        engineSettings.multipv = parseInt(multipvEdit, engineSettings.multipv);
                        engineSettings.threadsAuto = threadsAutoBox.isChecked();
                        engineSettings.threads = parseInt(threadsEdit, engineSettings.threads);
                        engineSettings.hashAuto = hashAutoBox.isChecked();
                        engineSettings.hashMb = parseInt(hashEdit, engineSettings.hashMb);
                        engineSettings.contempt = parseInt(contemptEdit, engineSettings.contempt);
                        engineSettings.clamp();
                        saveSettings();
                        toast("Engine settings saved — apply on next start");
                    }
                })
                .setNeutralButton("Reset to max strength", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        engineSettings = new EngineSettings();
                        saveSettings();
                        toast("Engine reset to max strength");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private EditText numberField(LinearLayout form, String label, String value) {
        TextView l = new TextView(this);
        l.setText(label);
        form.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        form.addView(e);
        return e;
    }

    private int parseInt(EditText e, int fallback) {
        try {
            return Integer.parseInt(e.getText().toString().trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    /**
     * If the previous run crashed, CrashHandler saved a report.
     * Offer it: share it, copy it, or dismiss it. Share/Copy/Dismiss
     * all delete the file, so it does not re-prompt on every launch.
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
                                Intent share = new Intent(Intent.ACTION_SEND);
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
        updateStartButton();
    }

    private void refreshPermissionText() {
        if (permissionText == null) return;
        boolean overlayAllowed = Settings.canDrawOverlays(this);
        String notif;
        if (Build.VERSION.SDK_INT < 33) {
            notif = "n/a below Android 13";
        } else {
            notif = checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED
                    ? "allowed" : "NOT allowed";
        }
        permissionText.setText("Permissions — Overlay: "
                + (overlayAllowed ? "allowed" : "NOT allowed")
                + " · Notifications: " + notif
                + " · Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + ")");
        if (overlayBtn != null) {
            overlayBtn.setVisibility(overlayAllowed ? View.GONE : View.VISIBLE);
        }
    }

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
            toast("Allow overlay first");
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
        int myColor = myColorPref;
        int checked = colorGroup.getCheckedRadioButtonId();
        if (checked == 2) myColor = -1;
        if (checked == 3) myColor = 0;
        myColorPref = myColor;
        saveSettings();
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
        i.putExtra(CaptureService.EXTRA_THINK_MS, engineSettings.thinkMs);
        i.putExtra(CaptureService.EXTRA_SKILL, engineSettings.skillLevel);
        i.putExtra(CaptureService.EXTRA_LIMIT, engineSettings.limitStrength);
        i.putExtra(CaptureService.EXTRA_ELO, engineSettings.elo);
        i.putExtra(CaptureService.EXTRA_MULTIPV, engineSettings.multipv);
        i.putExtra(CaptureService.EXTRA_THREADS_AUTO, engineSettings.threadsAuto);
        i.putExtra(CaptureService.EXTRA_THREADS, engineSettings.threads);
        i.putExtra(CaptureService.EXTRA_HASH_AUTO, engineSettings.hashAuto);
        i.putExtra(CaptureService.EXTRA_HASH_MB, engineSettings.hashMb);
        i.putExtra(CaptureService.EXTRA_CONTEMPT, engineSettings.contempt);
        i.putExtra(CaptureService.EXTRA_FEN, fen);
        startForeground(i);
        statusText.setText("Running — switch to your chess app. "
                + "The overlay arrow appears on your turn.");
        toast("Running — switch to your chess app");
        updateStartButton();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode,
                                              Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY) {
            toast(Settings.canDrawOverlays(this)
                    ? "Overlay allowed" : "Overlay still not allowed");
            refreshPermissionText();
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
