package com.palmgrade.rns.ui.grading;

import android.app.Activity;
import android.content.Intent;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.palmgrade.rns.R;
import com.palmgrade.rns.grading.BunchRecord;
import com.palmgrade.rns.grading.GpsLocationProvider;
import com.palmgrade.rns.grading.PhotoManager;
import com.palmgrade.rns.rns.RnsService;
import com.palmgrade.rns.storage.BunchRecordRepository;
import com.palmgrade.rns.ui.MainActivity;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GradingFragment
 *
 * The primary data-entry screen for oil palm bunch quality grading.
 *
 * Layout sections:
 *   1. Session info (Block ID, Harvester name)
 *   2. Grade inputs (6 categories, each with SeekBar + EditText)
 *   3. GPS display (auto-updating)
 *   4. Photo capture button
 *   5. Submit button (→ local save + LXMF TX)
 */
public class GradingFragment extends Fragment {

    private static final String TAG = "GradingFragment";

    // Grade categories in order
    private static final String[] GRADE_KEYS = {
        "ripe", "unripe", "overripe", "empty", "damaged", "rotten"
    };
    private static final String[] GRADE_LABELS = {
        "Ripe", "Unripe", "Overripe", "Empty", "Damaged", "Rotten"
    };
    private static final int[] GRADE_MAX = { 200, 100, 100, 50, 50, 50 };

    // UI refs
    private EditText etBlockId, etHarvester;
    private SeekBar[]  seekBars  = new SeekBar[6];
    private EditText[] gradeInputs = new EditText[6];
    private TextView tvTotal, tvGpsCoords, tvGpsAccuracy, tvGpsTime;
    private ImageView ivPhoto;
    private Button btnCapture, btnSubmit;
    private TextView tvSubmitStatus;

    // State
    private int[] gradeCounts = new int[6];
    private String photoPath = null;
    private Uri    photoUri  = null;
    private Location currentLocation = null;

    // Helpers
    private GpsLocationProvider gpsProvider;
    private PhotoManager photoManager;
    private BunchRecordRepository repository;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Camera launch
    private final ActivityResultLauncher<Uri> cameraLauncher =
        registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
            success -> {
                if (success) onPhotoCaptured();
            }
        );

    // -------------------------------------------------------------------------
    // Fragment lifecycle
    // -------------------------------------------------------------------------

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle saved) {
        return inflater.inflate(R.layout.fragment_grading, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etBlockId   = view.findViewById(R.id.et_block_id);
        etHarvester = view.findViewById(R.id.et_harvester);
        tvTotal     = view.findViewById(R.id.tv_total_bunches);
        tvGpsCoords = view.findViewById(R.id.tv_gps_coords);
        tvGpsAccuracy = view.findViewById(R.id.tv_gps_accuracy);
        tvGpsTime   = view.findViewById(R.id.tv_gps_time);
        ivPhoto     = view.findViewById(R.id.iv_photo_preview);
        btnCapture  = view.findViewById(R.id.btn_capture_photo);
        btnSubmit   = view.findViewById(R.id.btn_submit);
        tvSubmitStatus = view.findViewById(R.id.tv_submit_status);

        // Wire up grade rows (inflated dynamically from container)
        LinearLayout gradeContainer = view.findViewById(R.id.grade_container);
        setupGradeRows(gradeContainer);

        photoManager = new PhotoManager(requireContext());
        repository   = BunchRecordRepository.get(requireContext());
        gpsProvider  = new GpsLocationProvider(requireContext());

        // Restore persisted Block ID
        String savedBlock = requireContext()
            .getSharedPreferences("app_prefs", 0)
            .getString("last_block_id", "");
        if (!savedBlock.isEmpty()) etBlockId.setText(savedBlock);

        btnCapture.setOnClickListener(v -> capturePhoto());
        btnSubmit.setOnClickListener(v -> submitRecord());

        gpsProvider.start(gpsListener);
    }

    @Override
    public void onDestroyView() {
        gpsProvider.stop();
        super.onDestroyView();
    }

    // -------------------------------------------------------------------------
    // Grade rows
    // -------------------------------------------------------------------------

    private void setupGradeRows(LinearLayout container) {
        container.removeAllViews();
        for (int i = 0; i < GRADE_KEYS.length; i++) {
            final int idx = i;
            View row = getLayoutInflater().inflate(R.layout.item_grade_row, container, false);

            TextView tvLabel    = row.findViewById(R.id.tv_grade_label);
            SeekBar  seekBar    = row.findViewById(R.id.seekbar_grade);
            EditText etCount    = row.findViewById(R.id.et_grade_count);

            tvLabel.setText(GRADE_LABELS[i]);
            seekBar.setMax(GRADE_MAX[i]);
            seekBar.setProgress(gradeCounts[i]);
            etCount.setText(String.valueOf(gradeCounts[i]));

            seekBars[i]    = seekBar;
            gradeInputs[i] = etCount;

            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                    if (fromUser) {
                        gradeCounts[idx] = progress;
                        etCount.setText(String.valueOf(progress));
                        updateTotal();
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });

            etCount.setOnFocusChangeListener((v, hasFocus) -> {
                if (!hasFocus) {
                    int val = 0;
                    try { val = Integer.parseInt(etCount.getText().toString()); }
                    catch (NumberFormatException ignored) {}
                    val = Math.max(0, Math.min(val, GRADE_MAX[idx]));
                    gradeCounts[idx] = val;
                    seekBar.setProgress(val);
                    etCount.setText(String.valueOf(val));
                    updateTotal();
                }
            });

            container.addView(row);
        }
        updateTotal();
    }

    private void updateTotal() {
        int total = 0;
        for (int c : gradeCounts) total += c;
        if (tvTotal != null) tvTotal.setText("Total: " + total);
    }

    // -------------------------------------------------------------------------
    // Photo capture
    // -------------------------------------------------------------------------

    private void capturePhoto() {
        Object[] fileAndUri = photoManager.createPhotoFile();
        if (fileAndUri == null) {
            Toast.makeText(requireContext(), "Cannot create photo file", Toast.LENGTH_SHORT).show();
            return;
        }
        File file = (File) fileAndUri[0];
        photoUri  = (Uri) fileAndUri[1];
        photoPath = file.getAbsolutePath();
        cameraLauncher.launch(photoUri);
    }

    private void onPhotoCaptured() {
        if (photoPath == null) return;

        executor.execute(() -> {
            // Compress first
            photoManager.compressPhoto(photoPath);

            // Geotag with current GPS
            boolean geotagged = false;
            if (currentLocation != null) {
                geotagged = photoManager.geotag(photoPath, currentLocation);
            }

            final boolean finalTagged = geotagged;
            requireActivity().runOnUiThread(() -> {
                // Show preview
                android.graphics.Bitmap thumb = android.graphics.BitmapFactory.decodeFile(photoPath,
                    new android.graphics.BitmapFactory.Options() {{ inSampleSize = 4; }});
                if (thumb != null) ivPhoto.setImageBitmap(thumb);
                ivPhoto.setVisibility(View.VISIBLE);

                String msg = finalTagged ? "Photo captured & geotagged" : "Photo captured (no GPS)";
                btnCapture.setText("Retake photo");
                tvSubmitStatus.setText(msg);
            });
        });
    }

    // -------------------------------------------------------------------------
    // Submit
    // -------------------------------------------------------------------------

    private void submitRecord() {
        String blockId    = etBlockId.getText().toString().trim();
        String harvester  = etHarvester.getText().toString().trim();

        if (blockId.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter a Block ID", Toast.LENGTH_SHORT).show();
            return;
        }

        // Persist block ID for next session
        requireContext()
            .getSharedPreferences("app_prefs", 0)
            .edit()
            .putString("last_block_id", blockId)
            .apply();

        double lat = 0, lon = 0;
        float  acc = 999;
        if (currentLocation != null) {
            lat = currentLocation.getLatitude();
            lon = currentLocation.getLongitude();
            acc = currentLocation.getAccuracy();
        }

        // Get RNS address from service
        String rnsAddr = "";
        if (requireActivity() instanceof MainActivity) {
            RnsService svc = ((MainActivity) requireActivity()).getRnsService();
            if (svc != null) rnsAddr = svc.getIdentity().getLxmfAddressHex();
        }

        BunchRecord record = BunchRecord.create(
            blockId, harvester, rnsAddr,
            lat, lon, acc,
            gradeCounts[0], gradeCounts[1], gradeCounts[2],
            gradeCounts[3], gradeCounts[4], gradeCounts[5],
            photoPath
        );

        btnSubmit.setEnabled(false);
        tvSubmitStatus.setText("Saving...");

        repository.insert(record, () -> requireActivity().runOnUiThread(() -> {
            tvSubmitStatus.setText("Saved locally. Transmitting via LXMF...");
            transmit(record);
        }));
    }

    private void transmit(BunchRecord record) {
        if (!(requireActivity() instanceof MainActivity)) {
            finishSubmit("Saved (no service)", record.uuid);
            return;
        }
        RnsService svc = ((MainActivity) requireActivity()).getRnsService();
        if (svc == null) {
            finishSubmit("Saved (service unavailable)", record.uuid);
            return;
        }

        // Build destination address — broadcast / configured receiver
        byte[] dest = svc.getIdentity().getLxmfAddressBytes(); // placeholder: echo to self
        boolean sent = svc.sendLxmfMessage(dest, record.getLxmfTitle(), record.getLxmfContent());

        if (sent) {
            repository.markTransmitted(record.uuid);
            finishSubmit("Transmitted via LXMF ✓", record.uuid);
        } else {
            repository.incrementTxAttempts(record.uuid);
            finishSubmit("Queued for later TX (RNode offline)", record.uuid);
        }
    }

    private void finishSubmit(String statusMsg, String uuid) {
        requireActivity().runOnUiThread(() -> {
            tvSubmitStatus.setText(statusMsg);
            btnSubmit.setEnabled(true);
            Toast.makeText(requireContext(), "Record saved: " + uuid.substring(0, 8), Toast.LENGTH_SHORT).show();
            resetForm();
        });
    }

    private void resetForm() {
        for (int i = 0; i < gradeCounts.length; i++) {
            gradeCounts[i] = 0;
            if (seekBars[i] != null)    seekBars[i].setProgress(0);
            if (gradeInputs[i] != null) gradeInputs[i].setText("0");
        }
        updateTotal();
        photoPath = null;
        photoUri  = null;
        ivPhoto.setVisibility(View.GONE);
        btnCapture.setText("Capture proof photo");
    }

    // -------------------------------------------------------------------------
    // GPS listener
    // -------------------------------------------------------------------------

    private final GpsLocationProvider.GpsListener gpsListener = new GpsLocationProvider.GpsListener() {
        @Override
        public void onLocationUpdate(Location location) {
            currentLocation = location;
            if (tvGpsCoords == null) return;
            requireActivity().runOnUiThread(() -> {
                tvGpsCoords.setText(String.format(java.util.Locale.US,
                    "N %.4f°  E %.4f°",
                    location.getLatitude(), location.getLongitude()));
                tvGpsAccuracy.setText(String.format(java.util.Locale.US,
                    "±%.1f m", location.getAccuracy()));
                tvGpsTime.setText(
                    new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(new java.util.Date()));
            });
        }

        @Override
        public void onProviderStatusChanged(boolean available) {
            if (tvGpsCoords == null) return;
            requireActivity().runOnUiThread(() ->
                tvGpsCoords.setText(available ? "Acquiring GPS fix..." : "GPS unavailable"));
        }
    };
}
