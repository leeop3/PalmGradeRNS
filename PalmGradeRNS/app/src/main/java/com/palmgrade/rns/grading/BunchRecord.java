package com.palmgrade.rns.grading;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * BunchRecord
 *
 * Represents a single oil palm bunch grading submission.
 * Stored in SQLite via Room. Transmitted via LXMF as a compact CSV payload.
 *
 * CSV format (for LXMF content field):
 *   uuid,timestamp_iso,block_id,harvester,lat,lon,ripe,unripe,overripe,empty,damaged,rotten,photo_path
 */
@Entity(tableName = "bunch_records")
public class BunchRecord {

    @PrimaryKey
    @NonNull
    public String uuid;

    // Metadata
    public String blockId;
    public String harvesterName;
    public String harvesterRnsAddress;
    public long   timestampMs;
    public double latitude;
    public double longitude;
    public float  gpsAccuracyMeters;

    // Grading counts
    public int countRipe;
    public int countUnripe;
    public int countOverripe;
    public int countEmpty;
    public int countDamaged;
    public int countRotten;

    // Proof
    public String photoPath;       // Absolute path to JPEG on device
    public boolean photoGeotagged; // Did we write GPS EXIF tags?

    // Transmission state
    public boolean transmitted;    // Successfully sent via LXMF
    public long    transmittedAtMs;
    public int     txAttempts;

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    public static BunchRecord create(
        String blockId,
        String harvesterName,
        String harvesterRnsAddress,
        double lat,
        double lon,
        float  accuracyM,
        int ripe, int unripe, int overripe,
        int empty, int damaged, int rotten,
        String photoPath
    ) {
        BunchRecord r = new BunchRecord();
        r.uuid                 = UUID.randomUUID().toString();
        r.blockId              = blockId;
        r.harvesterName        = harvesterName;
        r.harvesterRnsAddress  = harvesterRnsAddress;
        r.timestampMs          = System.currentTimeMillis();
        r.latitude             = lat;
        r.longitude            = lon;
        r.gpsAccuracyMeters    = accuracyM;
        r.countRipe            = ripe;
        r.countUnripe          = unripe;
        r.countOverripe        = overripe;
        r.countEmpty           = empty;
        r.countDamaged         = damaged;
        r.countRotten          = rotten;
        r.photoPath            = photoPath;
        r.photoGeotagged       = (photoPath != null && !photoPath.isEmpty());
        r.transmitted          = false;
        r.txAttempts           = 0;
        return r;
    }

    // -------------------------------------------------------------------------
    // Serialization helpers
    // -------------------------------------------------------------------------

    /** Compact CSV line for LXMF transmission (≈160 bytes typical) */
    public String toCsv() {
        String ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .format(new Date(timestampMs));
        return String.format(Locale.US,
            "%s,%s,%s,%s,%.6f,%.6f,%d,%d,%d,%d,%d,%d",
            uuid, ts, esc(blockId), esc(harvesterName),
            latitude, longitude,
            countRipe, countUnripe, countOverripe, countEmpty, countDamaged, countRotten);
    }

    /** Header line for CSV export file */
    public static String csvHeader() {
        return "uuid,timestamp,block_id,harvester,latitude,longitude," +
               "ripe,unripe,overripe,empty,damaged,rotten";
    }

    /** Human-readable summary for History screen */
    public String getSummary() {
        int total = getTotalBunches();
        return String.format(Locale.US,
            "R:%d U:%d O:%d E:%d D:%d Ro:%d (total %d)",
            countRipe, countUnripe, countOverripe, countEmpty, countDamaged, countRotten, total);
    }

    public int getTotalBunches() {
        return countRipe + countUnripe + countOverripe + countEmpty + countDamaged + countRotten;
    }

    public String getFormattedTime() {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(timestampMs));
    }

    public String getFormattedDate() {
        return new SimpleDateFormat("dd MMM yyyy", Locale.US).format(new Date(timestampMs));
    }

    public String getFormattedCoords() {
        return String.format(Locale.US, "N %.4f° E %.4f°", latitude, longitude);
    }

    // -------------------------------------------------------------------------
    // LXMF message builder
    // -------------------------------------------------------------------------

    /** Build LXMF title for this record */
    public String getLxmfTitle() {
        return String.format(Locale.US, "GRADE/%s/%s", blockId, uuid.substring(0, 8));
    }

    /** Build LXMF content (CSV payload) for this record */
    public String getLxmfContent() {
        return toCsv();
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    private String esc(String s) {
        if (s == null) return "";
        return s.replace(",", ";").replace("\n", " ");
    }
}
