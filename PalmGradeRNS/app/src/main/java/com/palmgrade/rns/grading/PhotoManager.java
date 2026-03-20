package com.palmgrade.rns.grading;

import android.content.Context;
import android.location.Location;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * PhotoManager
 *
 * Creates camera intents and writes GPS EXIF tags to captured proof photos.
 *
 * Photos are stored in: app-private Pictures/ directory
 * Filename: PALM_yyyyMMdd_HHmmss.jpg
 *
 * EXIF tags written:
 *   - GPS_LATITUDE / GPS_LONGITUDE
 *   - GPS_LATITUDE_REF / GPS_LONGITUDE_REF
 *   - GPS_ALTITUDE (if available)
 *   - DATETIME_ORIGINAL
 *   - GPS_TIMESTAMP
 *   - IMAGE_DESCRIPTION = "PalmGrade proof photo"
 *
 * Camera intent is launched by the Activity using the Uri returned by createPhotoFile().
 */
public class PhotoManager {

    private static final String TAG = "PhotoManager";
    private static final String AUTHORITY_SUFFIX = ".fileprovider";

    private final Context context;

    public PhotoManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Create a new empty photo file and return its Uri (for camera intent).
     * @return [file, uri] pair, or null on error
     */
    public Object[] createPhotoFile() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String filename = "PALM_" + ts + ".jpg";

        File dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (dir == null) {
            dir = new File(context.getFilesDir(), "Pictures");
        }
        if (!dir.exists()) dir.mkdirs();

        File photoFile = new File(dir, filename);
        try {
            if (photoFile.createNewFile()) {
                String authority = context.getPackageName() + AUTHORITY_SUFFIX;
                Uri uri = FileProvider.getUriForFile(context, authority, photoFile);
                Log.d(TAG, "Photo file created: " + photoFile.getAbsolutePath());
                return new Object[]{ photoFile, uri };
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to create photo file", e);
        }
        return null;
    }

    /**
     * Write GPS and timestamp EXIF metadata to a captured photo.
     * Call after the camera intent returns successfully.
     *
     * @param photoPath  absolute path of the captured JPEG
     * @param location   GPS fix at time of capture
     * @return true if EXIF was written successfully
     */
    public boolean geotag(String photoPath, Location location) {
        if (photoPath == null || location == null) return false;
        File file = new File(photoPath);
        if (!file.exists() || file.length() == 0) return false;

        try {
            ExifInterface exif = new ExifInterface(photoPath);

            // GPS coordinates
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE,
                toDmsRational(Math.abs(location.getLatitude())));
            exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF,
                location.getLatitude() >= 0 ? "N" : "S");

            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE,
                toDmsRational(Math.abs(location.getLongitude())));
            exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF,
                location.getLongitude() >= 0 ? "E" : "W");

            if (location.hasAltitude()) {
                long altCm = Math.abs((long)(location.getAltitude() * 100));
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE,
                    altCm + "/100");
                exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF,
                    location.getAltitude() < 0 ? "1" : "0");
            }

            // Timestamp
            String dateStr = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                .format(new Date());
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateStr);
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateStr);

            // GPS timestamp (UTC)
            String gpsTime = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String gpsDate = new SimpleDateFormat("yyyy:MM:dd", Locale.US).format(new Date());
            exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, gpsTime);
            exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, gpsDate);

            // Description
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION,
                String.format(Locale.US,
                    "PalmGrade proof photo | GPS %.6f,%.6f | acc %.1fm",
                    location.getLatitude(), location.getLongitude(),
                    location.getAccuracy()));

            // Accuracy note (custom field via MakerNote not standardized — store in UserComment)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT,
                String.format(Locale.US, "GPS_ACC=%.1fm", location.getAccuracy()));

            exif.saveAttributes();
            Log.d(TAG, "EXIF written to " + photoPath);
            return true;

        } catch (IOException e) {
            Log.e(TAG, "EXIF write failed", e);
            return false;
        }
    }

    /**
     * Compress a JPEG to reduce file size for storage.
     * Target: ~250-400 KB suitable for field use (original may be 5-10 MB).
     */
    public boolean compressPhoto(String photoPath) {
        try {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = 4; // Reduce to 1/4 dimensions = 1/16 pixels

            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(photoPath, opts);
            if (bmp == null) return false;

            java.io.FileOutputStream fos = new java.io.FileOutputStream(photoPath);
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 65, fos);
            fos.close();
            bmp.recycle();

            Log.d(TAG, "Photo compressed: " + new File(photoPath).length() / 1024 + " KB");
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Compression failed", e);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // EXIF helpers
    // -------------------------------------------------------------------------

    /** Convert decimal degrees to DMS rational string for EXIF */
    private String toDmsRational(double decimal) {
        int degrees = (int) decimal;
        double minutesDouble = (decimal - degrees) * 60.0;
        int minutes = (int) minutesDouble;
        double secondsDouble = (minutesDouble - minutes) * 60.0;
        int secondsNum = (int)(secondsDouble * 1000);

        return degrees + "/1," + minutes + "/1," + secondsNum + "/1000";
    }
}
