package com.palmgrade.rns.storage;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import com.palmgrade.rns.grading.BunchRecord;

import java.util.List;

/**
 * BunchRecordDao
 *
 * Room DAO for all bunch grading record persistence.
 * All queries run on the executor threads managed by BunchRecordRepository —
 * never call these from the main thread.
 */
@Dao
public interface BunchRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insert(BunchRecord record);

    @Update
    void update(BunchRecord record);

    /** All records, newest first. LiveData — auto-updates observers on change. */
    @Query("SELECT * FROM bunch_records ORDER BY timestampMs DESC")
    LiveData<List<BunchRecord>> getAllLive();

    /** All records, newest first. Blocking — use on background thread. */
    @Query("SELECT * FROM bunch_records ORDER BY timestampMs DESC")
    List<BunchRecord> getAll();

    /** Today's records (device local time), newest first. Blocking. */
    @Query("SELECT * FROM bunch_records WHERE " +
           "date(timestampMs/1000, 'unixepoch', 'localtime') = date('now','localtime') " +
           "ORDER BY timestampMs DESC")
    List<BunchRecord> getTodayRecords();

    /** Today's records as LiveData. */
    @Query("SELECT * FROM bunch_records WHERE " +
           "date(timestampMs/1000, 'unixepoch', 'localtime') = date('now','localtime') " +
           "ORDER BY timestampMs DESC")
    LiveData<List<BunchRecord>> getTodayRecordsLive();

    /** Records not yet transmitted, oldest first (FIFO for retry). */
    @Query("SELECT * FROM bunch_records WHERE transmitted = 0 ORDER BY timestampMs ASC")
    List<BunchRecord> getUnsentRecords();

    /** Total record count for today. */
    @Query("SELECT COUNT(*) FROM bunch_records WHERE " +
           "date(timestampMs/1000, 'unixepoch', 'localtime') = date('now','localtime')")
    int getTodayCount();

    /** Transmitted record count for today. */
    @Query("SELECT COUNT(*) FROM bunch_records WHERE " +
           "date(timestampMs/1000, 'unixepoch', 'localtime') = date('now','localtime') " +
           "AND transmitted = 1")
    int getTodaySentCount();

    /** Sum of all bunch counts for today (for export stats display). */
    @Query("SELECT COALESCE(SUM(countRipe + countUnripe + countOverripe + " +
           "countEmpty + countDamaged + countRotten), 0) FROM bunch_records WHERE " +
           "date(timestampMs/1000, 'unixepoch', 'localtime') = date('now','localtime')")
    int getTodayBunchTotal();

    @Query("SELECT * FROM bunch_records WHERE uuid = :uuid LIMIT 1")
    BunchRecord getById(String uuid);

    @Query("UPDATE bunch_records SET transmitted = 1, transmittedAtMs = :atMs, " +
           "txAttempts = txAttempts + 1 WHERE uuid = :uuid")
    void markTransmitted(String uuid, long atMs);

    @Query("UPDATE bunch_records SET txAttempts = txAttempts + 1 WHERE uuid = :uuid")
    void incrementTxAttempts(String uuid);
}
