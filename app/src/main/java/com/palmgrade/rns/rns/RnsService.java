package com.palmgrade.rns.rns;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.palmgrade.rns.R;
import com.palmgrade.rns.bluetooth.RNodeBluetoothManager;
import com.palmgrade.rns.ui.MainActivity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RnsService
 *
 * Foreground service that owns the Bluetooth connection to RNode and manages:
 *  - Auto-announce timer (default: every 10 minutes)
 *  - TX queue draining
 *  - Incoming packet dispatching to registered listeners
 *  - Connection keepalive
 *
 * Bound service pattern: Activities bind to retrieve the manager instances.
 */
public class RnsService extends Service implements RNodeBluetoothManager.RNodeListener {

    private static final String TAG = "RnsService";
    private static final String CHANNEL_ID = "rns_service";
    private static final int NOTIF_ID = 1001;

    public static final String ACTION_START = "com.palmgrade.rns.START";
    public static final String ACTION_STOP  = "com.palmgrade.rns.STOP";
    public static final long   AUTO_ANNOUNCE_INTERVAL_MS = 10 * 60 * 1000L; // 10 min

    // Binder
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public RnsService getService() { return RnsService.this; }
    }

    // Core components
    private RNodeBluetoothManager btManager;
    private RnsIdentity identity;

    // Auto-announce
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean autoAnnounce = true;

    // Listeners for UI
    private final List<RNodeBluetoothManager.RNodeListener> uiListeners = new CopyOnWriteArrayList<>();

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        identity   = new RnsIdentity(this);
        btManager  = new RNodeBluetoothManager(this);
        btManager.setListener(this);
        startForeground(NOTIF_ID, buildNotification("RNS offline — tap to connect"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            btManager.disconnect();
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAnnounceTimer();
        btManager.disconnect();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public RNodeBluetoothManager getBluetoothManager() { return btManager; }
    public RnsIdentity getIdentity() { return identity; }

    public void addUiListener(RNodeBluetoothManager.RNodeListener l)    { uiListeners.add(l); }
    public void removeUiListener(RNodeBluetoothManager.RNodeListener l) { uiListeners.remove(l); }

    public void connect(BluetoothDevice device) {
        btManager.connect(device);
    }

    public void disconnect() {
        btManager.disconnect();
        stopAnnounceTimer();
    }

    public void setAutoAnnounce(boolean enabled) {
        autoAnnounce = enabled;
        if (enabled && btManager.getState() == RNodeBluetoothManager.ConnectionState.CONNECTED) {
            scheduleAnnounce();
        } else {
            stopAnnounceTimer();
        }
    }

    public boolean isAutoAnnounce() { return autoAnnounce; }

    public void sendAnnounceNow() {
        if (btManager.getState() != RNodeBluetoothManager.ConnectionState.CONNECTED) return;
        byte[] announcePacket = identity.buildAnnouncePacket();
        btManager.sendLxmfPacket(announcePacket);
        Log.d(TAG, "Announce sent, lxmf_addr=" + identity.getLxmfAddressHex());
    }

    public boolean sendLxmfMessage(byte[] destinationAddress, String title, String content) {
        if (btManager.getState() != RNodeBluetoothManager.ConnectionState.CONNECTED) {
            Log.w(TAG, "Cannot send — not connected");
            return false;
        }
        byte[] pkt = identity.buildLxmfMessage(destinationAddress, title, content);
        return btManager.sendLxmfPacket(pkt);
    }

    // -------------------------------------------------------------------------
    // RNodeListener → relay to UI listeners
    // -------------------------------------------------------------------------

    @Override
    public void onConnectionStateChanged(RNodeBluetoothManager.ConnectionState state, String deviceName) {
        String notifMsg = switch (state) {
            case CONNECTED    -> "Connected to " + deviceName + " · RNS active";
            case CONNECTING   -> "Connecting to " + deviceName + "...";
            case DISCONNECTED -> "RNS offline — tap to connect";
            case ERROR        -> "Connection error — check RNode";
        };
        updateNotification(notifMsg);

        if (state == RNodeBluetoothManager.ConnectionState.CONNECTED) {
            if (autoAnnounce) scheduleAnnounce();
            // Brief delay lets the RNode finish radio init before we TX
            handler.postDelayed(this::sendAnnounceNow, 1500);
        } else {
            stopAnnounceTimer();
        }

        for (RNodeBluetoothManager.RNodeListener l : uiListeners) {
            l.onConnectionStateChanged(state, deviceName);
        }
    }

    @Override
    public void onDataReceived(byte[] data) {
        Log.d(TAG, "Received " + data.length + " bytes from RNode");
        for (RNodeBluetoothManager.RNodeListener l : uiListeners) {
            l.onDataReceived(data);
        }
    }

    @Override
    public void onError(String message) {
        updateNotification("Error: " + message);
        for (RNodeBluetoothManager.RNodeListener l : uiListeners) {
            l.onError(message);
        }
    }

    // -------------------------------------------------------------------------
    // Auto-announce timer
    // -------------------------------------------------------------------------

    private final Runnable announceRunnable = new Runnable() {
        @Override
        public void run() {
            sendAnnounceNow();
            if (autoAnnounce) scheduleAnnounce();
        }
    };

    private void scheduleAnnounce() {
        handler.removeCallbacks(announceRunnable);
        handler.postDelayed(announceRunnable, AUTO_ANNOUNCE_INTERVAL_MS);
    }

    private void stopAnnounceTimer() {
        handler.removeCallbacks(announceRunnable);
    }

    // -------------------------------------------------------------------------
    // Notification helpers
    // -------------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "RNS Connection", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Reticulum Network Stack radio connection");
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent tapIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PalmGrade RNS")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(NOTIF_ID, buildNotification(text));
    }
}
