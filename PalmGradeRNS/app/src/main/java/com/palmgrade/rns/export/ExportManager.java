package com.palmgrade.rns.export;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import com.palmgrade.rns.grading.BunchRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * ExportManager
 *
 * Builds the end-of-day ZIP package:
 *
 *   palmgrade_YYYY-MM-DD.zip
 *     ├── grading_YYYY-MM-DD.csv      ← all records for the day
 *     ├── photos/
 *     │   ├── PALM_20260319_081400.jpg
 *     │   └── ...
 *     └── manifest.json               ← checksums + record count + identity
 *
 * manifest.json schema:
 * {
 *   "date": "2026-03-19",
 *   "generated_at": "2026-03-19T17:00:00",
 *   "record_count": 7,
 *   "harvester_rns": "b4:2f:a1:09:7e:cc:3d:84:f1:22",
 *   "records": [
 *     { "uuid": "...", "csv_sha256": "...", "photo_sha256": "...", "photo_file": "..." }
 *   ]
 * }
 */
public class ExportManager {

    private static final String TAG = "Export";

    public interface ExportCallback {
        void onProgress(String message, int percent);
        void onComplete(File zipFile);
        void onError(String message);
    }

    private final Context context;

    public ExportManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Build the ZIP package asynchronously.
     * Runs on a background thread; callbacks dispatched on caller thread.
     */
    public void buildDailyExport(
        List<BunchRecord> records,
        String harvesterRnsAddress,
        ExportCallback callback
    ) {
        new Thread(() -> {
            try {
                File zipFile = buildZip(records, harvesterRnsAddress, callback);
                callback.onComplete(zipFile);
            } catch (Exception e) {
                Log.e(TAG, "Export failed", e);
                callback.onError(e.getMessage());
            }
        }, "ExportThread").start();
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    private File buildZip(
        List<BunchRecord> records,
        String harvesterRnsAddress,
        ExportCallback cb
    ) throws Exception {

        String dateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String zipName = "palmgrade_" + dateStr + ".zip";

        File exportDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (exportDir == null) exportDir = new File(context.getFilesDir(), "exports");
        if (!exportDir.exists()) exportDir.mkdirs();

        File zipFile = new File(exportDir, zipName);
        if (zipFile.exists()) zipFile.delete();

        cb.onProgress("Building CSV...", 10);
        String csvContent = buildCsv(records);

        cb.onProgress("Writing package...", 30);

        JSONArray recordManifest = new JSONArray();

        try (ZipOutputStream zos = new ZipOutputStream(
                new java.io.BufferedOutputStream(
                    new java.io.FileOutputStream(zipFile)))) {

            zos.setLevel(6);

            // 1. CSV data file
            String csvName = "grading_" + dateStr + ".csv";
            byte[] csvBytes = csvContent.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            addBytesToZip(zos, csvName, csvBytes);
            cb.onProgress("Added CSV...", 40);

            // 2. Photos
            int photoCount = 0;
            for (int i = 0; i < records.size(); i++) {
                BunchRecord r = records.get(i);
                if (r.photoPath != null && !r.photoPath.isEmpty()) {
                    File photo = new File(r.photoPath);
                    if (photo.exists()) {
                        String photoName = "photos/" + photo.getName();
                        byte[] photoBytes = readFile(photo);
                        addBytesToZip(zos, photoName, photoBytes);

                        // Record checksum in manifest
                        JSONObject entry = new JSONObject();
                        entry.put("uuid", r.uuid);
                        entry.put("block_id", r.blockId);
                        entry.put("timestamp", r.getFormattedDate() + " " + r.getFormattedTime());
                        entry.put("photo_file", photoName);
                        entry.put("photo_sha256", sha256Hex(photoBytes));
                        entry.put("csv_sha256", sha256Hex(r.toCsv().getBytes()));
                        entry.put("transmitted", r.transmitted);
                        recordManifest.put(entry);
                        photoCount++;
                    }
                }

                int pct = 40 + (int)((double)(i + 1) / records.size() * 45);
                cb.onProgress("Adding photo " + (i+1) + "/" + records.size() + "...", pct);
            }

            // 3. Manifest JSON
            cb.onProgress("Building manifest...", 88);
            JSONObject manifest = new JSONObject();
            manifest.put("date", dateStr);
            manifest.put("generated_at",
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date()));
            manifest.put("record_count", records.size());
            manifest.put("photo_count", photoCount);
            manifest.put("harvester_rns", harvesterRnsAddress);
            manifest.put("app_version", "1.0.0");
            manifest.put("records", recordManifest);

            byte[] manifestBytes = manifest.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            addBytesToZip(zos, "manifest.json", manifestBytes);

            cb.onProgress("Finalizing...", 95);
        }

        long sizeKb = zipFile.length() / 1024;
        Log.i(TAG, "Export ZIP created: " + zipFile.getAbsolutePath() + " (" + sizeKb + " KB)");
        return zipFile;
    }

    // -------------------------------------------------------------------------
    // WiFi transfer
    // -------------------------------------------------------------------------

    /**
     * Send the ZIP file to an office server via simple HTTP POST.
     * Office server should accept multipart/form-data on port 8080.
     */
    public void transferViaWifi(File zipFile, String serverIp, int port,
                                 java.util.function.Consumer<String> resultCallback) {
        new Thread(() -> {
            String url = "http://" + serverIp + ":" + port + "/upload";
            try {
                java.net.URL u = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(30_000);

                String boundary = "PalmGrade" + System.currentTimeMillis();
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                try (java.io.DataOutputStream dos = new java.io.DataOutputStream(conn.getOutputStream())) {
                    dos.writeBytes("--" + boundary + "\r\n");
                    dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\""
                        + zipFile.getName() + "\"\r\n");
                    dos.writeBytes("Content-Type: application/zip\r\n\r\n");

                    byte[] buf = new byte[4096];
                    int n;
                    try (FileInputStream fis = new FileInputStream(zipFile)) {
                        while ((n = fis.read(buf)) != -1) dos.write(buf, 0, n);
                    }
                    dos.writeBytes("\r\n--" + boundary + "--\r\n");
                }

                int code = conn.getResponseCode();
                if (code == 200 || code == 201) {
                    resultCallback.accept("OK: " + zipFile.getName() + " uploaded successfully");
                } else {
                    resultCallback.accept("Server error: HTTP " + code);
                }
                conn.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "WiFi transfer failed", e);
                resultCallback.accept("Transfer failed: " + e.getMessage());
            }
        }, "WiFiTransfer").start();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String buildCsv(List<BunchRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append(BunchRecord.csvHeader()).append('\n');
        for (BunchRecord r : records) sb.append(r.toCsv()).append('\n');
        return sb.toString();
    }

    private void addBytesToZip(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setSize(data.length);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private byte[] readFile(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int offset = 0, n;
            while (offset < data.length &&
                   (n = fis.read(data, offset, data.length - offset)) != -1) {
                offset += n;
            }
        }
        return data;
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        } catch (Exception e) {
            return "error";
        }
    }
}
