package com.palmgrade.rns;

import android.app.Application;
import android.util.Log;

import com.palmgrade.rns.storage.BunchRecordRepository;

/**
 * PalmGradeApp
 *
 * Application class. Initialises singletons at startup.
 * Keep this lean — field devices have limited RAM.
 */
public class PalmGradeApp extends Application {

    private static final String TAG = "PalmGradeApp";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "PalmGrade RNS starting");

        // Pre-warm the Room database connection pool on a background thread
        // so the first DB query from the UI doesn't block
        new Thread(() -> {
            BunchRecordRepository.get(this);
            Log.d(TAG, "Database ready");
        }, "DB-Init").start();
    }
}
