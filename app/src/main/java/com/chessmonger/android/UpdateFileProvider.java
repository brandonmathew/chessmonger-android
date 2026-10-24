package com.chessmonger.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal file provider for the downloaded update APK, v1.11.0.
 *
 * Why not the usual FileProvider? This app is built with the
 * manual pipeline (javac + D8, no Gradle, no AndroidX), so
 * androidx.core.content.FileProvider is not on the classpath.
 * This provider serves exactly one file — the update APK in
 * the app cache dir — and nothing else, so the system
 * installer can read it via a content:// URI. A file:// URI
 * would throw FileUriExposedException on API 24+.
 * The provider is exported=false with grantUriPermissions,
 * and the installer intent carries FLAG_GRANT_READ_URI_PERMISSION.
 */
public final class UpdateFileProvider extends ContentProvider {

    public static final String AUTHORITY =
            "com.chessmonger.android.fileprovider";
    public static final String UPDATE_FILE_NAME = "update.apk";

    private static final UriMatcher MATCHER = new UriMatcher(
            UriMatcher.NO_MATCH);

    static {
        MATCHER.addURI(AUTHORITY, "update.apk", 1);
    }

    public static Uri updateUri() {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .path("update.apk")
                .build();
    }

    public static File updateFile(android.content.Context context) {
        File dir = new File(context.getCacheDir(), "updates");
        return new File(dir, UPDATE_FILE_NAME);
    }

    @Override public boolean onCreate() {
        return true;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode)
            throws FileNotFoundException {
        if (MATCHER.match(uri) != 1) {
            throw new FileNotFoundException("Unknown URI: " + uri);
        }
        android.content.Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException("No context");
        }
        File file = updateFile(context);
        if (!file.exists()) {
            throw new FileNotFoundException("Update not downloaded");
        }
        return ParcelFileDescriptor.open(
                file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override public Cursor query(Uri uri, String[] projection,
                                  String selection, String[] selectionArgs,
                                  String sortOrder) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override public int delete(Uri uri, String selection,
                                String[] selectionArgs) {
        return 0;
    }

    @Override public int update(Uri uri, ContentValues values,
                                String selection, String[] selectionArgs) {
        return 0;
    }
}
