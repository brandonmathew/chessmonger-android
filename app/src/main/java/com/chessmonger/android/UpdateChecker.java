package com.chessmonger.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * GitHub Releases auto-update, v1.11.0 (replaces the v1.9.0
 * raw-main version.json check).
 *
 * Why Releases, not raw main? version.json on main is a file
 * that changes in place, and GitHub's raw CDN caches it, so
 * a fresh push did not show up right away (v1.10.0 was pushed
 * but raw still served v1.9.0). A release asset URL is unique
 * to its release, so it cannot serve a previous version by
 * mistake, and release assets allow up to 2 GB (no 50 MB
 * warning) while keeping 62 MB APKs out of git history.
 *
 * How it works:
 *  - On every fresh start (MainActivity.onCreate, NOT onResume)
 *    and on the manual "Check for updates" button, a background
 *    thread calls the GitHub Releases API for the latest release.
 *  - That release must carry a "version.json" asset (same fields
 *    as before: versionCode, versionName, sha256, notes) and an
 *    APK asset. versionCode from that file is compared to the
 *    installed versionCode; the APK asset URL from the API is
 *    authoritative for the download (version.json's apkUrl is
 *    kept for the old raw updater and for humans, and is ignored
 *    here).
 *  - If newer, a dialog offers "Download & install". Tapping it
 *    downloads the APK in-app (no browser), verifies its SHA-256
 *    against version.json, then opens the system installer
 *    automatically. Honest limit, stated in the dialog and in
 *    the README: Android does not let a normal app install
 *    silently. The user still taps "Install" once in the system
 *    installer, and the very first time they must allow
 *    "Install unknown apps" for ChessMonger (a system settings
 *    page the dialog opens for them). After that, updates are
 *    download + one Install tap, with no browser step.
 *  - Failures (no release yet, offline, bad JSON, bad SHA,
 *    download error) are silent on fresh start and a toast on
 *    manual check. A failed SHA check deletes the file and
 *    never launches the installer.
 */
public final class UpdateChecker {

    /** GitHub Releases API: latest release for this repo. */
    public static final String RELEASES_API_URL =
            "https://api.github.com/repos/brandonmathew/"
                    + "chessmonger-android/releases/latest";

    private UpdateChecker() {}

    public static final class Update {
        public final int versionCode;
        public final String versionName;
        public final String apkUrl;
        public final String notes;
        public final String sha256;

        public Update(int versionCode, String versionName, String apkUrl,
                      String notes, String sha256) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.notes = notes;
            this.sha256 = sha256;
        }
    }

    public static int installedVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) {
                return (int) info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String fetchText(String urlString) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "ChessMonger-Android");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Fetch the latest release and its version.json asset. Null on any failure. */
    public static Update fetchUpdate() {
        try {
            String apiText = fetchText(RELEASES_API_URL);
            if (apiText == null) return null;
            JSONObject release = new JSONObject(apiText);
            if (release.optBoolean("draft", false)
                    || release.optBoolean("prerelease", false)) {
                return null;
            }
            String tag = release.optString("tag_name", "");
            String body = release.optString("body", "");
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) return null;
            String apkUrl = null;
            String versionJsonUrl = null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String name = asset.optString("name", "");
                String download = asset.optString("browser_download_url", "");
                if (download.isEmpty()) continue;
                if (name.equals("version.json")) {
                    versionJsonUrl = download;
                } else if (name.endsWith(".apk")
                        && name.startsWith("ChessMongerAndroid-")) {
                    apkUrl = download;
                }
            }
            if (apkUrl == null || versionJsonUrl == null) return null;
            String versionText = fetchText(versionJsonUrl);
            if (versionText == null) return null;
            JSONObject json = new JSONObject(versionText);
            int code = json.optInt("versionCode", 0);
            if (code <= 0) return null;
            String notes = json.optString("notes", "");
            if (notes.isEmpty()) notes = body;
            return new Update(code, json.optString("versionName", tag),
                    apkUrl, notes, json.optString("sha256", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String sha256Hex(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream in = new BufferedInputStream(new FileInputStream(file));
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            digest.update(buffer, 0, read);
        }
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static void launchInstaller(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(UpdateFileProvider.updateUri(),
                "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity,
                    "Could not open the installer — the downloaded "
                            + "APK is in the app cache",
                    Toast.LENGTH_LONG).show();
        }
    }

    private static void downloadAndInstall(final Activity activity,
                                           final Update update) {
        final AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle("Downloading update")
                .setMessage("Downloading " + update.versionName
                        + "… please wait. The installer will open "
                        + "automatically when it finishes.")
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(new Runnable() {
            @Override public void run() {
                String error = null;
                try {
                    File file = UpdateFileProvider.updateFile(activity);
                    File dir = file.getParentFile();
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    HttpURLConnection conn = null;
                    try {
                        URL url = new URL(update.apkUrl);
                        conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(30000);
                        conn.setRequestProperty("User-Agent",
                                "ChessMonger-Android");
                        if (conn.getResponseCode() != 200) {
                            throw new Exception("HTTP "
                                    + conn.getResponseCode());
                        }
                        InputStream in = new BufferedInputStream(
                                conn.getInputStream());
                        OutputStream out = new FileOutputStream(file);
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                        out.close();
                        in.close();
                    } finally {
                        if (conn != null) conn.disconnect();
                    }
                    if (update.sha256 != null && !update.sha256.isEmpty()) {
                        String actual = sha256Hex(file);
                        if (!actual.equalsIgnoreCase(update.sha256)) {
                            file.delete();
                            throw new Exception("SHA-256 mismatch — "
                                    + "download discarded, installer "
                                    + "not launched");
                        }
                    }
                } catch (Exception e) {
                    error = e.getMessage();
                }
                final String finalError = error;
                activity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try { progress.dismiss(); } catch (Exception ignored) {}
                        if (activity.isFinishing()) return;
                        if (finalError != null) {
                            Toast.makeText(activity,
                                    "Update download failed: " + finalError,
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        launchInstaller(activity);
                    }
                });
            }
        }).start();
    }

    /**
     * Check on a background thread, show a dialog on the UI thread
     * only if a newer version exists. Call from MainActivity.onCreate
     * (fresh start) and from the manual "Check for updates" button.
     */
    public static void check(final Activity activity,
                             final boolean showToastOnNoUpdate) {
        final int installed = installedVersionCode(activity);
        new Thread(new Runnable() {
            @Override public void run() {
                final Update update = fetchUpdate();
                activity.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (activity.isFinishing()) return;
                        if (update == null) {
                            if (showToastOnNoUpdate) {
                                Toast.makeText(activity,
                                        "Update check failed — no release "
                                                + "found, or no connection",
                                        Toast.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        if (update.versionCode <= installed) {
                            if (showToastOnNoUpdate) {
                                Toast.makeText(activity,
                                        "ChessMonger is up to date ("
                                                + update.versionName + ")",
                                        Toast.LENGTH_SHORT).show();
                            }
                            return;
                        }
                        String message = "A new version is available: "
                                + update.versionName + "\n\n"
                                + (update.notes.isEmpty()
                                        ? "" : update.notes + "\n\n")
                                + "Tap Download & install to download "
                                + "it in the app and open the system "
                                + "installer automatically. You will "
                                + "still tap Install once in the "
                                + "system installer — Android does "
                                + "not allow a normal app to install "
                                + "silently.";
                        new AlertDialog.Builder(activity)
                                .setTitle("ChessMonger update available")
                                .setMessage(message)
                                .setPositiveButton("Download & install",
                                        new DialogInterface.OnClickListener() {
                                            @Override public void onClick(
                                                    DialogInterface d, int w) {
                                                // First-time gate: Android
                                                // requires the user to allow
                                                // "Install unknown apps" for
                                                // this app before any install
                                                // intent works. Ask before
                                                // downloading 62 MB, not after.
                                                PackageManager pm = activity
                                                        .getPackageManager();
                                                boolean allowed = false;
                                                try {
                                                    allowed = pm
                                                            .canRequestPackageInstalls();
                                                } catch (Exception ignored) {
                                                }
                                                if (!allowed) {
                                                    new AlertDialog.Builder(activity)
                                                            .setTitle("Allow installs from ChessMonger")
                                                            .setMessage("Android requires you to allow ChessMonger to install apps once, in Settings. After you allow it, come back and check for updates again — the download will then start.")
                                                            .setPositiveButton("Open Settings",
                                                                    new DialogInterface.OnClickListener() {
                                                                        @Override public void onClick(
                                                                                DialogInterface d2, int w2) {
                                                                            Intent i = new Intent(
                                                                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                                                                    Uri.parse("package:"
                                                                                            + activity.getPackageName()));
                                                                            try {
                                                                                activity.startActivity(i);
                                                                            } catch (Exception e) {
                                                                                Toast.makeText(activity,
                                                                                        "Could not open install settings",
                                                                                        Toast.LENGTH_SHORT).show();
                                                                            }
                                                                        }
                                                                    })
                                                            .setNegativeButton("Later", null)
                                                            .show();
                                                    return;
                                                }
                                                downloadAndInstall(activity, update);
                                            }
                                        })
                                .setNegativeButton("Later", null)
                                .show();
                    }
                });
            }
        }).start();
    }
}
