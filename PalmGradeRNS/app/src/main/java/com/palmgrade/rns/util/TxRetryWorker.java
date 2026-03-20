package com.palmgrade.rns.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.palmgrade.rns.grading.BunchRecord;
import com.palmgrade.rns.rns.RnsService;
import com.palmgrade.rns.storage.BunchRecordRepository;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * TxRetryWorker
 *
 * Periodically checks for unsent records and retries LXMF transmission
 * when the RNode connection is active.
 *
 * Retry strategy:
 *   - Max 5 attempts per record
 *   - Retry interval: every 2 minutes while connected
 *   - Records with txAttempts >= 5 are flagged as "failed" in UI
 *     but kept in DB for manual export
 */
public class TxRetryWorker {

    private static final String TAG = "TxRetry";
    private static final int    MAX_ATTEMPTS    = 5;
    private static final long   RETRY_INTERVAL  = 2 * 60L; // seconds

    private final Context context;
    private final BunchRecordRepository repository;
    private ScheduledExecutorService scheduler;
    private RnsService rnsService;

    public TxRetryWorker(Context context) {
        this.context    = context.getApplicationContext();
        this.repository = BunchRecordRepository.get(context);
    }

    public void setRnsService(RnsService svc) { this.rnsService = svc; }

    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::retryUnsent, 30, RETRY_INTERVAL, TimeUnit.SECONDS);
        Log.d(TAG, "TX retry worker started");
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void retryUnsent() {
        if (rnsService == null) return;
        if (rnsService.getBluetoothManager().getState()
                != com.palmgrade.rns.bluetooth.RNodeBluetoothManager.ConnectionState.CONNECTED) {
            return;
        }

        repository.getUnsentRecords(records -> {
            if (records.isEmpty()) return;
            Log.d(TAG, "Retrying " + records.size() + " unsent records");

            for (BunchRecord r : records) {
                if (r.txAttempts >= MAX_ATTEMPTS) {
                    Log.w(TAG, "Record " + r.uuid.substring(0, 8) + " exceeded max attempts, skipping");
                    continue;
                }

                byte[] dest = rnsService.getIdentity().getLxmfAddressBytes();
                boolean sent = rnsService.sendLxmfMessage(dest, r.getLxmfTitle(), r.getLxmfContent());

                if (sent) {
                    repository.markTransmitted(r.uuid);
                    Log.d(TAG, "Retry TX success: " + r.uuid.substring(0, 8));
                } else {
                    repository.incrementTxAttempts(r.uuid);
                    Log.d(TAG, "Retry TX queued: " + r.uuid.substring(0, 8)
                        + " (attempt " + (r.txAttempts + 1) + ")");
                    break; // Don't flood the queue; try one at a time
                }
            }
        });
    }
}


// =============================================================================

/**
 * SessionPrefs
 *
 * Typed SharedPreferences wrapper for persistent session/app state.
 */
class SessionPrefs {

    private static final String PREFS = "session";

    private final SharedPreferences sp;

    public SessionPrefs(Context ctx) {
        sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getLastBlockId()                { return sp.getString("last_block_id", ""); }
    public void   setLastBlockId(String id)       { sp.edit().putString("last_block_id", id).apply(); }

    public String getHarvesterName()              { return sp.getString("harvester_name", ""); }
    public void   setHarvesterName(String name)   { sp.edit().putString("harvester_name", name).apply(); }

    public String getReceiverRnsAddress()         { return sp.getString("receiver_rns_addr", ""); }
    public void   setReceiverRnsAddress(String a) { sp.edit().putString("receiver_rns_addr", a).apply(); }

    public String getOfficeServerIp()             { return sp.getString("office_ip", "192.168.1.100"); }
    public void   setOfficeServerIp(String ip)    { sp.edit().putString("office_ip", ip).apply(); }

    public int    getBandwidthIndex()             { return sp.getInt("bw_index", 0); }
    public void   setBandwidthIndex(int i)        { sp.edit().putInt("bw_index", i).apply(); }

    public int    getSpreadingFactor()            { return sp.getInt("sf", 9); }
    public void   setSpreadingFactor(int sf)      { sp.edit().putInt("sf", sf).apply(); }

    public int    getCodingRate()                 { return sp.getInt("cr", 5); }
    public void   setCodingRate(int cr)           { sp.edit().putInt("cr", cr).apply(); }

    public boolean isAutoAnnounce()               { return sp.getBoolean("auto_announce", true); }
    public void    setAutoAnnounce(boolean b)     { sp.edit().putBoolean("auto_announce", b).apply(); }
}
