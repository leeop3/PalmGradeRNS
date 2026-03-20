package com.palmgrade.rns.ui.export;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.palmgrade.rns.R;
import com.palmgrade.rns.export.ExportManager;
import com.palmgrade.rns.rns.RnsService;
import com.palmgrade.rns.storage.BunchRecordRepository;
import com.palmgrade.rns.ui.MainActivity;

import java.io.File;

/**
 * ExportFragment
 *
 * Tab 3 — End-of-day data export.
 *
 * Workflow:
 *   1. Screen loads → queries today's record + bunch counts for the header stats.
 *   2. "Package as ZIP" → builds palmgrade_YYYY-MM-DD.zip containing:
 *        grading_YYYY-MM-DD.csv, photos/, manifest.json
 *   3. "Send to office server" → HTTP multipart POST to the configured LAN IP.
 *
 * Stats wiring:
 *   tv_stat_bunches  — today's total bunch count (all grade categories summed)
 *   tv_stat_records  — today's record count
 *   tv_export_stats  — subtitle line showing "N records · M transmitted"
 */
public class ExportFragment extends Fragment {

    private Button      btnPackage, btnSendWifi;
    private TextView    tvStatus, tvExportStats;
    private TextView    tvStatBunches, tvStatRecords;
    private TextView    tvCsvSize, tvPhotoSize;
    private EditText    etServerIp;
    private ProgressBar progressBar;

    private File lastZipFile;
    private BunchRecordRepository repository;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_export, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        btnPackage    = view.findViewById(R.id.btn_package);
        btnSendWifi   = view.findViewById(R.id.btn_send_wifi);
        tvStatus      = view.findViewById(R.id.tv_export_status);
        tvExportStats = view.findViewById(R.id.tv_export_stats);
        tvStatBunches = view.findViewById(R.id.tv_stat_bunches);
        tvStatRecords = view.findViewById(R.id.tv_stat_records);
        tvCsvSize     = view.findViewById(R.id.tv_csv_size);
        tvPhotoSize   = view.findViewById(R.id.tv_photo_size);
        etServerIp    = view.findViewById(R.id.et_server_ip);
        progressBar   = view.findViewById(R.id.export_progress);

        repository = BunchRecordRepository.get(requireContext());

        btnPackage.setOnClickListener(v -> buildPackage());
        btnSendWifi.setOnClickListener(v -> sendViaWifi());
        btnSendWifi.setEnabled(false);

        loadStats();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadStats();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Stats
    // ─────────────────────────────────────────────────────────────────────────

    private void loadStats() {
        if (repository == null) return;

        // Record count + sent count → subtitle line
        repository.getTodayStats((total, sent) ->
            requireActivity().runOnUiThread(() -> {
                if (tvExportStats != null)
                    tvExportStats.setText(
                        "Today: " + total + " record" + (total == 1 ? "" : "s")
                        + " · " + sent + " transmitted");
                if (tvStatRecords != null)
                    tvStatRecords.setText(String.valueOf(total));
                if (tvCsvSize != null)
                    tvCsvSize.setText(total + " records · calculating…");
            })
        );

        // Total bunch count → header metric card
        repository.getTodayBunchTotal(bunchTotal ->
            requireActivity().runOnUiThread(() -> {
                if (tvStatBunches != null)
                    tvStatBunches.setText(String.valueOf(bunchTotal));
                if (tvPhotoSize != null) {
                    repository.getTodayStats((total, sent) ->
                        requireActivity().runOnUiThread(() ->
                            tvPhotoSize.setText(total + " photos · GPS + timestamp EXIF")
                        )
                    );
                }
            })
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ZIP build
    // ─────────────────────────────────────────────────────────────────────────

    private void buildPackage() {
        btnPackage.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        tvStatus.setText("Collecting records…");

        // Resolve RNS address for manifest
        String rnsAddr = "";
        if (requireActivity() instanceof MainActivity) {
            RnsService svc = ((MainActivity) requireActivity()).getRnsService();
            if (svc != null) rnsAddr = svc.getIdentity().getLxmfAddressHex();
        }
        final String finalRnsAddr = rnsAddr;

        repository.getTodayRecords(records -> {
            if (records.isEmpty()) {
                requireActivity().runOnUiThread(() -> {
                    tvStatus.setText("No records for today");
                    btnPackage.setEnabled(true);
                    progressBar.setVisibility(View.GONE);
                });
                return;
            }

            ExportManager exporter = new ExportManager(requireContext());
            exporter.buildDailyExport(records, finalRnsAddr,
                new ExportManager.ExportCallback() {

                    @Override
                    public void onProgress(String message, int percent) {
                        requireActivity().runOnUiThread(() -> {
                            tvStatus.setText(message);
                            progressBar.setProgress(percent);
                        });
                    }

                    @Override
                    public void onComplete(File zipFile) {
                        lastZipFile = zipFile;
                        long sizeKb = zipFile.length() / 1024;
                        requireActivity().runOnUiThread(() -> {
                            tvStatus.setText("✓ " + zipFile.getName()
                                + "  (" + sizeKb + " KB)");
                            if (tvCsvSize != null)
                                tvCsvSize.setText(records.size() + " records · "
                                    + sizeKb + " KB total");
                            btnPackage.setEnabled(true);
                            btnSendWifi.setEnabled(true);
                            progressBar.setVisibility(View.GONE);
                            Toast.makeText(requireContext(),
                                "ZIP ready — " + sizeKb + " KB",
                                Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        requireActivity().runOnUiThread(() -> {
                            tvStatus.setText("Export error: " + message);
                            btnPackage.setEnabled(true);
                            progressBar.setVisibility(View.GONE);
                        });
                    }
                });
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WiFi transfer
    // ─────────────────────────────────────────────────────────────────────────

    private void sendViaWifi() {
        if (lastZipFile == null) {
            Toast.makeText(requireContext(),
                getString(R.string.err_build_zip_first), Toast.LENGTH_SHORT).show();
            return;
        }

        String ip = etServerIp.getText().toString().trim();
        if (ip.isEmpty()) {
            Toast.makeText(requireContext(),
                getString(R.string.err_no_server_ip), Toast.LENGTH_SHORT).show();
            return;
        }

        btnSendWifi.setEnabled(false);
        tvStatus.setText("Connecting to " + ip + "…");

        ExportManager exporter = new ExportManager(requireContext());
        exporter.transferViaWifi(lastZipFile, ip, 8080, result ->
            requireActivity().runOnUiThread(() -> {
                tvStatus.setText(result);
                btnSendWifi.setEnabled(true);
                Toast.makeText(requireContext(), result, Toast.LENGTH_LONG).show();
            })
        );
    }
}
