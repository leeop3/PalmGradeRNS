package com.palmgrade.rns.storage;

import android.content.Context;

import androidx.lifecycle.LiveData;

import com.palmgrade.rns.grading.BunchRecord;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * BunchRecordRepository
 *
 * Single source of truth for all bunch grading record access.
 * Wraps the Room DAO, ensuring all database work runs on a background
 * thread pool — never on the main/UI thread.
 *
 * Consumers receive results via callbacks invoked on the executor thread.
 * Callers that need to update UI must post back with runOnUiThread() or a Handler.
 * LiveData queries are exempt — Room dispatches those automatically.
 */
public class BunchRecordRepository {

    private static volatile BunchRecordRepository INSTANCE;

    private final BunchRecordDao dao;
    private final ExecutorService executor = Executors.newFixedThreadPool(3);

    private BunchRecordRepository(Context context) {
        dao = AppDatabase.getInstance(context).bunchRecordDao();
    }

    public static BunchRecordRepository get(Context context) {
        if (INSTANCE == null) {
            synchronized (BunchRecordRepository.class) {
                if (INSTANCE == null) {
                    INSTANCE = new BunchRecordRepository(context.getApplicationContext());
                }
            }
        }
        return INSTANCE;
    }

    // ── Write operations ─────────────────────────────────────────────────────

    public void insert(BunchRecord record, Runnable onDone) {
        executor.execute(() -> {
            dao.insert(record);
            if (onDone != null) onDone.run();
        });
    }

    public void update(BunchRecord record) {
        executor.execute(() -> dao.update(record));
    }

    public void markTransmitted(String uuid) {
        executor.execute(() -> dao.markTransmitted(uuid, System.currentTimeMillis()));
    }

    public void incrementTxAttempts(String uuid) {
        executor.execute(() -> dao.incrementTxAttempts(uuid));
    }

    // ── LiveData queries ──────────────────────────────────────────────────────

    public LiveData<List<BunchRecord>> getAllLive() {
        return dao.getAllLive();
    }

    public LiveData<List<BunchRecord>> getTodayLive() {
        return dao.getTodayRecordsLive();
    }

    // ── Callback queries ──────────────────────────────────────────────────────

    public void getTodayRecords(Consumer<List<BunchRecord>> callback) {
        executor.execute(() -> callback.accept(dao.getTodayRecords()));
    }

    public void getUnsentRecords(Consumer<List<BunchRecord>> callback) {
        executor.execute(() -> callback.accept(dao.getUnsentRecords()));
    }

    /** callback(totalRecords, sentRecords) — both for today only. */
    public void getTodayStats(BiConsumer<Integer, Integer> callback) {
        executor.execute(() -> {
            int total = dao.getTodayCount();
            int sent  = dao.getTodaySentCount();
            callback.accept(total, sent);
        });
    }

    /** Total bunch count across all grade categories for today. */
    public void getTodayBunchTotal(Consumer<Integer> callback) {
        executor.execute(() -> callback.accept(dao.getTodayBunchTotal()));
    }
}
