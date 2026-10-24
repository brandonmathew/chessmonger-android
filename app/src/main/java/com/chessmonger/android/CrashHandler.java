package com.chessmonger.android;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * Last-resort crash capture for ChessMonger Android.
 *
 * Installed once from ChessMongerApp (Application.onCreate, so it is
 * in place before any activity or service can fail): when any thread
 * dies with an uncaught exception, the full report is written to
 * app-private crash-reports/ storage, then the system's own handler
 * still runs so Android behaves normally (dialog / process death).
 * On the next launch MainActivity finds the report and offers
 * Share / Copy / Dismiss — Share and Copy delete the file, so it
 * does not re-prompt. That turns an opaque "it crashed" into an
 * exact, fixable bug report: exception, thread, and where it
 * happened, plus the ChessMonger context that matters (capture
 * colour, think time, last tracker status).
 *
 * At most MAX_REPORTS files are kept, newest first. Reporting must
 * never make a crash worse: every step is guarded, and a failure
 * to write simply falls through to the previous handler.
 */
public final class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String DIR_NAME = "crash-reports";
    private static final int MAX_REPORTS = 5;

    // v1.5.0: volatile — these are written from the capture and
    // engine threads and read from a crashing thread.
    private static volatile String currentScreen = "(unknown)";
    private static volatile String trackerContext = "(not started)";
    private static boolean installed = false;

    private final Context appContext;
    private final Thread.UncaughtExceptionHandler previous;

    private CrashHandler(Context context,
                         Thread.UncaughtExceptionHandler previous) {
        this.appContext = context.getApplicationContext();
        this.previous = previous;
    }

    public static synchronized void install(Context context) {
        if (installed) return;
        installed = true;
        Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(
                new CrashHandler(context, previous));
    }

    /** Called by ChessMongerApp as activities come and go. */
    public static void setCurrentScreen(String screen) {
        currentScreen = screen == null ? "(unknown)" : screen;
    }

    /** Called by CaptureService whenever its state changes. */
    public static void setTrackerContext(String context) {
        trackerContext = context == null ? "(unknown)" : context;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable error) {
        try {
            String report = buildReport(thread, error);
            File dir = reportDir(appContext);
            // v1.5.0: prune AFTER a successful write, not before.
            // Pruning first deleted the oldest report even when
            // the new write then failed, losing a report for
            // nothing. Also use millisecond stamps: two crashes
            // in the same second used to overwrite each other.
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",
                    Locale.US).format(new Date());
            File file = new File(dir, "crash-" + stamp + ".txt");
            Writer writer = new OutputStreamWriter(
                    new FileOutputStream(file), "UTF-8");
            try {
                writer.write(report);
            } finally {
                writer.close();
            }
            pruneOldReports(dir);
        } catch (Throwable ignored) {
            // Reporting must never make a crash worse.
        } finally {
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        }
    }

    private String buildReport(Thread thread, Throwable error) {
        StringBuilder out = new StringBuilder();
        out.append("ChessMonger Android crash report\n");
        out.append("=================================\n");
        out.append("When: ").append(new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()))
                .append('\n');
        try {
            PackageInfo info = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0);
            out.append("App: ").append(info.versionName)
                    .append(" (").append(info.versionCode).append(")\n");
        } catch (Exception ignored) {
            out.append("App: (unknown version)\n");
        }
        out.append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Device: ").append(Build.MANUFACTURER).append(' ')
                .append(Build.MODEL).append('\n');
        out.append("Screen: ").append(currentScreen).append('\n');
        out.append("Tracker: ").append(trackerContext).append('\n');
        out.append("Thread: ").append(thread.getName()).append('\n');
        out.append("\nException: ").append(error).append('\n');
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        out.append(sw.toString());
        // Chained causes, if any, are already in the trace above.
        return out.toString();
    }

    private static File reportDir(Context context) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static File[] sortedReports(Context context) {
        File[] files = reportDir(context)
                .listFiles(new java.io.FilenameFilter() {
                    @Override public boolean accept(
                            java.io.File d, String name) {
                        return name.startsWith("crash-")
                                && name.endsWith(".txt");
                    }
                });
        if (files == null) return new File[0];
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return b.getName().compareTo(a.getName());
            }
        });
        return files;
    }

    private static void pruneOldReports(File dir) {
        File[] files = dir.listFiles(new java.io.FilenameFilter() {
            @Override public boolean accept(java.io.File d, String name) {
                return name.startsWith("crash-")
                        && name.endsWith(".txt");
            }
        });
        if (files == null || files.length < MAX_REPORTS) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });
        for (int i = 0; i <= files.length - MAX_REPORTS; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    /** The newest saved report, or null when there is none. */
    public static File latestReport(Context context) {
        File[] files = sortedReports(context);
        return files.length == 0 ? null : files[0];
    }

    public static String readReport(File file) {
        try {
            FileInputStream in = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            in.close();
            return new String(data, 0, read, "UTF-8");
        } catch (Exception e) {
            return "(could not read the report: " + e.getMessage() + ")";
        }
    }

    public static void deleteReport(File file) {
        if (file != null) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
