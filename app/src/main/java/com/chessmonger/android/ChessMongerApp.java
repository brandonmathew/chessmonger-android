package com.chessmonger.android;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/**
 * Application entry point: installs the crash handler before
 * anything else can fail, and keeps track of which screen is in
 * front so crash reports can name it. Registered in the manifest
 * as android:name=".ChessMongerApp" — without that attribute this
 * class never runs and no crash is ever captured.
 */
public class ChessMongerApp extends Application {

    @Override public void onCreate() {
        super.onCreate();
        CrashHandler.install(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                CrashHandler.setCurrentScreen(
                        activity.getClass().getSimpleName());
            }

            @Override public void onActivityCreated(Activity activity,
                                                    Bundle state) {
            }

            @Override public void onActivityStarted(Activity activity) {
            }

            @Override public void onActivityPaused(Activity activity) {
            }

            @Override public void onActivityStopped(Activity activity) {
            }

            @Override public void onActivitySaveInstanceState(
                    Activity activity, Bundle state) {
            }

            @Override public void onActivityDestroyed(Activity activity) {
            }
        });
    }
}
