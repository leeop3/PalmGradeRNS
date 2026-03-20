package com.palmgrade.rns.storage;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import android.content.Context;

import com.palmgrade.rns.grading.BunchRecord;

/**
 * AppDatabase
 *
 * Room database definition. Single instance via companion singleton pattern.
 *
 * Schema version history:
 *   v1 — initial schema (BunchRecord)
 *
 * If you add columns to BunchRecord in a future version, increment the version
 * number and provide a Migration object, or use fallbackToDestructiveMigration()
 * for development builds only.
 */
@Database(
    entities  = { BunchRecord.class },
    version   = 1,
    exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase INSTANCE;

    public abstract BunchRecordDao bunchRecordDao();

    public static AppDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            AppDatabase.class,
                            "palmgrade.db")
                        // Development only — swap for proper Migration in release
                        .fallbackToDestructiveMigration()
                        .build();
                }
            }
        }
        return INSTANCE;
    }
}
