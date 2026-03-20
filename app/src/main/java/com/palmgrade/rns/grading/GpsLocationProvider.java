package com.palmgrade.rns.grading;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import androidx.core.app.ActivityCompat;

/**
 * GpsLocationProvider
 *
 * Wraps Android LocationManager for GPS-only operation (no network/cellular).
 * Designed for field use where GPS is the only reliable source.
 *
 * Usage:
 *   provider.start(listener);
 *   Location loc = provider.getLastKnownLocation();
 *   provider.stop();
 */
public class GpsLocationProvider {

    private static final String TAG = "GPS";
    private static final long   MIN_UPDATE_MS  = 5_000;   // 5 seconds
    private static final float  MIN_UPDATE_M   = 5.0f;    // 5 metres

    public interface GpsListener {
        void onLocationUpdate(Location location);
        void onProviderStatusChanged(boolean available);
    }

    private final Context context;
    private final LocationManager locationManager;
    private GpsListener listener;
    private Location lastLocation;
    private boolean active = false;

    public GpsLocationProvider(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void start(GpsListener l) {
        this.listener = l;
        if (!hasPermission()) {
            Log.w(TAG, "Location permission not granted");
            return;
        }
        try {
            // Request GPS updates
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_UPDATE_MS,
                MIN_UPDATE_M,
                locationListener
            );
            active = true;

            // Seed with last known
            Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (last != null) {
                lastLocation = last;
                if (listener != null) listener.onLocationUpdate(last);
            }
            Log.d(TAG, "GPS started");
        } catch (SecurityException e) {
            Log.e(TAG, "GPS start failed - permission", e);
        }
    }

    public void stop() {
        if (active) {
            locationManager.removeUpdates(locationListener);
            active = false;
            Log.d(TAG, "GPS stopped");
        }
    }

    public Location getLastKnownLocation() {
        if (lastLocation != null) return lastLocation;
        if (!hasPermission()) return null;
        try {
            return locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        } catch (SecurityException e) {
            return null;
        }
    }

    public boolean isActive() { return active; }
    public boolean isGpsEnabled() {
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
    }

    public boolean hasPermission() {
        return ActivityCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // -------------------------------------------------------------------------
    // Location listener
    // -------------------------------------------------------------------------

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            lastLocation = location;
            if (listener != null) listener.onLocationUpdate(location);
            Log.d(TAG, String.format("GPS fix: %.6f, %.6f ±%.1fm",
                location.getLatitude(), location.getLongitude(), location.getAccuracy()));
        }

        @Override
        public void onProviderEnabled(String provider) {
            if (listener != null) listener.onProviderStatusChanged(true);
        }

        @Override
        public void onProviderDisabled(String provider) {
            if (listener != null) listener.onProviderStatusChanged(false);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // Deprecated in API 29, kept for compatibility
        }
    };
}
