package com.carhelper.fullscreen;

import android.util.Log;

/** 车机端统一日志前缀。 */
public final class U {
    public static final String TAG = "CarHelperFs";

    private U() {
    }

    public static void log(String s) {
        try {
            Log.i(TAG, s);
        } catch (Throwable ignored) {
        }
    }
}
