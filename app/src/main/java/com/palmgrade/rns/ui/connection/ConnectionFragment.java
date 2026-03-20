package com.palmgrade.rns.ui.connection;

import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.fragment.app.Fragment;

import com.palmgrade.rns.R;
import com.palmgrade.rns.bluetooth.RNodeBluetoothManager;
import com.palmgrade.rns.rns.RnsIdentity;
import com.palmgrade.rns.rns.RnsService;
import com.palmgrade.rns.ui.MainActivity;

import java.util.List;

/**
 * ConnectionFragment
 *
 * Tab 0 — Radio & BT settings.
 *
 * Identity section behaviour (matching Sideband):
 *   - LXMF address and identity hash are DISPLAY ONLY — no EditText, no editable field.
 *   - Both shown as 32-char lowercase hex in a monospace read-only TextView.
 *   - Copy buttons let the user copy each value to clipboard.
 *   - "New identity" button opens a confirmation AlertDialog that warns the
 *     user their current LXMF address will be permanently replaced before
 *     calling identity.regenerate().
 *   - Nickname / display name remains user-editable (it does not affect keys).
 */
public class ConnectionFragment extends Fragment
        implements RNodeBluetoothManager.RNodeListener {

    private Spinner   spDevices;
    private Button    btnConnect, btnAnnounce, btnApplyRadio;
    private TextView  tvConnStatus, tvTxQueue;

    // Identity — all read-only display
    private TextView  tvLxmfAddress;      // 32-char hex, read-only
    private TextView  tvIdentityHash;     // 32-char hex, read-only
    private Button    btnCopyAddress;
    private Button    btnCopyIdentity;
    private Button    btnNewIdentity;     // triggers confirmation dialog
    private EditText  etNickname;         // only editable field in this card

    // Radio config
    private Spinner   spBandwidth;
    private EditText  etSf, etCr;

    // Announce
    private SwitchCompat swAutoAnnounce;

    private List<BluetoothDevice> deviceList;

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle saved) {
        return inflater.inflate(R.layout.fragment_connection, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // BT card
        spDevices      = view.findViewById(R.id.sp_bt_devices);
        btnConnect     = view.findViewById(R.id.btn_connect);
        tvConnStatus   = view.findViewById(R.id.tv_connection_status);
        tvTxQueue      = view.findViewById(R.id.tv_tx_queue);
        view.findViewById(R.id.btn_refresh_devices)
            .setOnClickListener(v -> loadDeviceList());

        // Identity card — display only
        tvLxmfAddress  = view.findViewById(R.id.tv_lxmf_address);
        tvIdentityHash = view.findViewById(R.id.tv_identity_hash);
        btnCopyAddress = view.findViewById(R.id.btn_copy_address);
        btnCopyIdentity= view.findViewById(R.id.btn_copy_identity);
        btnNewIdentity = view.findViewById(R.id.btn_new_identity);
        etNickname     = view.findViewById(R.id.et_nickname);

        // Announce card
        btnAnnounce    = view.findViewById(R.id.btn_announce_now);
        swAutoAnnounce = view.findViewById(R.id.sw_auto_announce);

        // Radio card
        spBandwidth    = view.findViewById(R.id.sp_bandwidth);
        etSf           = view.findViewById(R.id.et_sf);
        etCr           = view.findViewById(R.id.et_cr);
        btnApplyRadio  = view.findViewById(R.id.btn_apply_radio);

        // Bandwidth spinner
        ArrayAdapter<String> bwAdapter = new ArrayAdapter<>(requireContext(),
            android.R.layout.simple_spinner_item,
            RNodeBluetoothManager.RadioConfig.BANDWIDTH_LABELS);
        bwAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spBandwidth.setAdapter(bwAdapter);

        // Wire listeners
        btnConnect.setOnClickListener(v -> toggleConnection());
        btnAnnounce.setOnClickListener(v -> sendAnnounce());
        btnApplyRadio.setOnClickListener(v -> applyRadioConfig());
        swAutoAnnounce.setOnCheckedChangeListener((cb, on) -> setAutoAnnounce(on));

        btnCopyAddress.setOnClickListener(v -> copyToClipboard(
            "LXMF address", tvLxmfAddress.getText().toString()));
        btnCopyIdentity.setOnClickListener(v -> copyToClipboard(
            "Identity hash", tvIdentityHash.getText().toString()));

        btnNewIdentity.setOnClickListener(v -> confirmNewIdentity());

        // Nickname — save on focus-loss
        etNickname.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) saveNickname();
        });

        loadDeviceList();
        refreshUi();
    }

    @Override
    public void onResume() {
        super.onResume();
        RnsService svc = getService();
        if (svc != null) svc.addUiListener(this);
        refreshUi();
    }

    @Override
    public void onPause() {
        saveNickname();
        RnsService svc = getService();
        if (svc != null) svc.removeUiListener(this);
        super.onPause();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BT connection
    // ─────────────────────────────────────────────────────────────────────────

    private void loadDeviceList() {
        RnsService svc = getService();
        if (svc == null) return;
        deviceList = svc.getBluetoothManager().getPairedDevices();

        String[] names = new String[deviceList.size()];
        for (int i = 0; i < deviceList.size(); i++)
            names[i] = deviceList.get(i).getName() != null
                ? deviceList.get(i).getName()
                : deviceList.get(i).getAddress();

        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(),
            android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spDevices.setAdapter(a);

        if (deviceList.isEmpty()) {
            Toast.makeText(requireContext(),
                "No paired devices. Pair RNode in Android BT settings first.",
                Toast.LENGTH_LONG).show();
        }
    }

    private void toggleConnection() {
        RnsService svc = getService();
        if (svc == null) return;

        if (svc.getBluetoothManager().getState()
                == RNodeBluetoothManager.ConnectionState.CONNECTED) {
            svc.disconnect();
        } else {
            int idx = spDevices.getSelectedItemPosition();
            if (deviceList == null || deviceList.isEmpty() || idx < 0) {
                Toast.makeText(requireContext(),
                    getString(R.string.err_no_bt_device), Toast.LENGTH_SHORT).show();
                return;
            }
            svc.connect(deviceList.get(idx));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Announce
    // ─────────────────────────────────────────────────────────────────────────

    private void sendAnnounce() {
        RnsService svc = getService();
        if (svc == null) return;
        svc.sendAnnounceNow();
        Toast.makeText(requireContext(), "Announce sent", Toast.LENGTH_SHORT).show();
    }

    private void setAutoAnnounce(boolean enabled) {
        RnsService svc = getService();
        if (svc != null) svc.setAutoAnnounce(enabled);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Radio config
    // ─────────────────────────────────────────────────────────────────────────

    private void applyRadioConfig() {
        RnsService svc = getService();
        if (svc == null) return;

        RNodeBluetoothManager.RadioConfig cfg = new RNodeBluetoothManager.RadioConfig();
        cfg.frequencyMhz   = 433.025;
        cfg.bandwidthIndex = spBandwidth.getSelectedItemPosition();
        try { cfg.spreadingFactor = Integer.parseInt(etSf.getText().toString()); }
        catch (NumberFormatException e) { cfg.spreadingFactor = 9; }
        try { cfg.codingRate = Integer.parseInt(etCr.getText().toString()); }
        catch (NumberFormatException e) { cfg.codingRate = 5; }

        svc.getBluetoothManager().applyRadioConfig(cfg);
        Toast.makeText(requireContext(), "Radio config applied: " + cfg,
            Toast.LENGTH_SHORT).show();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Identity — read-only display + controlled regeneration
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Shows a destructive-action confirmation dialog before regenerating.
     * Mirrors Sideband's warning that the old LXMF address will be lost.
     */
    private void confirmNewIdentity() {
        new AlertDialog.Builder(requireContext())
            .setTitle("Create new identity?")
            .setMessage(
                "This will permanently replace your current identity and LXMF address.\n\n" +
                "Anyone who has your current address will no longer be able to reach you " +
                "until you share the new address with them.\n\n" +
                "This action cannot be undone.")
            .setPositiveButton("Create new identity", (d, w) -> {
                RnsService svc = getService();
                if (svc != null) {
                    svc.getIdentity().regenerate();
                    refreshIdentityDisplay(svc.getIdentity());
                    Toast.makeText(requireContext(),
                        "New identity created", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void saveNickname() {
        RnsService svc = getService();
        if (svc == null || etNickname == null) return;
        String name = etNickname.getText().toString().trim();
        svc.getIdentity().setNickname(name);
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager cm = (ClipboardManager)
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(requireContext(), label + " copied", Toast.LENGTH_SHORT).show();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI refresh
    // ─────────────────────────────────────────────────────────────────────────

    private void refreshUi() {
        RnsService svc = getService();
        if (svc == null || tvConnStatus == null) return;

        // Connection status
        RNodeBluetoothManager.ConnectionState st =
            svc.getBluetoothManager().getState();
        boolean connected = st == RNodeBluetoothManager.ConnectionState.CONNECTED;
        tvConnStatus.setText(connected ? "CONNECTED" : st.name());
        tvConnStatus.setBackgroundResource(
            connected ? R.drawable.bg_pill_green : R.drawable.bg_pill_amber);
        tvConnStatus.setTextColor(getResources().getColor(
            connected ? R.color.text_success : R.color.text_warning, null));
        btnConnect.setText(connected
            ? getString(R.string.btn_disconnect)
            : getString(R.string.btn_connect));

        // Identity — read-only
        refreshIdentityDisplay(svc.getIdentity());

        // Radio config
        RNodeBluetoothManager.RadioConfig cfg =
            svc.getBluetoothManager().getRadioConfig();
        if (etSf != null) etSf.setText(String.valueOf(cfg.spreadingFactor));
        if (etCr != null) etCr.setText(String.valueOf(cfg.codingRate));
        if (spBandwidth != null) spBandwidth.setSelection(cfg.bandwidthIndex);

        // Announce
        if (swAutoAnnounce != null) swAutoAnnounce.setChecked(svc.isAutoAnnounce());
    }

    private void refreshIdentityDisplay(RnsIdentity identity) {
        if (tvLxmfAddress == null || tvIdentityHash == null) return;

        // Both displayed as flat 32-char hex strings — no separators, no editing
        tvLxmfAddress.setText(identity.getLxmfAddressHex());
        tvIdentityHash.setText(identity.getIdentityHashHex());

        // Populate nickname field only if it's currently empty (avoid overwriting
        // what the user is actively typing)
        if (etNickname != null && etNickname.getText().toString().isEmpty()) {
            etNickname.setText(identity.getNickname());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RNodeListener callbacks
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void onConnectionStateChanged(
            RNodeBluetoothManager.ConnectionState state, String deviceName) {
        requireActivity().runOnUiThread(this::refreshUi);
    }

    @Override
    public void onDataReceived(byte[] data) {
        // Update TX queue display if needed
        requireActivity().runOnUiThread(() -> {
            if (tvTxQueue != null) {
                // Queue depth could be surfaced here via RnsService in future
            }
        });
    }

    @Override
    public void onError(String msg) {
        requireActivity().runOnUiThread(() ->
            Toast.makeText(requireContext(), "RNS: " + msg, Toast.LENGTH_SHORT).show());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private RnsService getService() {
        if (!(requireActivity() instanceof MainActivity)) return null;
        return ((MainActivity) requireActivity()).getRnsService();
    }
}
