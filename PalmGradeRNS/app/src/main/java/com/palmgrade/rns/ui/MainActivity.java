package com.palmgrade.rns.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;
import com.palmgrade.rns.R;
import com.palmgrade.rns.rns.RnsService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MainActivity
 *
 * Host for the 4-tab UI:
 *   0 → ConnectionFragment  (Radio & BT settings)
 *   1 → GradingFragment     (Bunch checker / data entry)
 *   2 → HistoryFragment     (Record list)
 *   3 → ExportFragment      (ZIP export & WiFi transfer)
 *
 * Owns the RnsService binding and permission requests.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // Tabs config
    private static final String[] TAB_LABELS = { "Radio", "Grade", "History", "Export" };
    private static final int[] TAB_ICONS = {
        R.drawable.ic_radio,
        R.drawable.ic_leaf,
        R.drawable.ic_history,
        R.drawable.ic_export
    };

    // Service binding
    private RnsService rnsService;
    private boolean    serviceBound = false;

    // Required permissions
    private static final String[] REQUIRED_PERMISSIONS;
    static {
        List<String> perms = new ArrayList<>();
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        perms.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT);
            perms.add(Manifest.permission.BLUETOOTH_SCAN);
        } else {
            perms.add(Manifest.permission.BLUETOOTH);
            perms.add(Manifest.permission.BLUETOOTH_ADMIN);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        REQUIRED_PERMISSIONS = perms.toArray(new String[0]);
    }

    private final ActivityResultLauncher<String[]> permissionLauncher =
        registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            this::onPermissionsResult
        );

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setupViewPager();
        startAndBindService();
        requestPermissionsIfNeeded();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    // -------------------------------------------------------------------------
    // ViewPager2 + TabLayout
    // -------------------------------------------------------------------------

    private void setupViewPager() {
        ViewPager2 viewPager = findViewById(R.id.view_pager);
        TabLayout  tabLayout = findViewById(R.id.tab_layout);

        MainPagerAdapter adapter = new MainPagerAdapter(this);
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(3); // Keep all fragments alive

        new TabLayoutMediator(tabLayout, viewPager, (tab, pos) -> {
            tab.setText(TAB_LABELS[pos]);
            tab.setIcon(TAB_ICONS[pos]);
        }).attach();
    }

    // -------------------------------------------------------------------------
    // Service
    // -------------------------------------------------------------------------

    private void startAndBindService() {
        Intent intent = new Intent(this, RnsService.class);
        intent.setAction(RnsService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    public RnsService getRnsService() { return rnsService; }
    public boolean    isServiceBound() { return serviceBound; }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            RnsService.LocalBinder lb = (RnsService.LocalBinder) binder;
            rnsService  = lb.getService();
            serviceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            rnsService   = null;
            serviceBound = false;
        }
    };

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private void requestPermissionsIfNeeded() {
        List<String> missing = new ArrayList<>();
        for (String perm : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        if (!missing.isEmpty()) {
            permissionLauncher.launch(missing.toArray(new String[0]));
        }
    }

    private void onPermissionsResult(Map<String, Boolean> results) {
        boolean allGranted = true;
        for (Map.Entry<String, Boolean> e : results.entrySet()) {
            if (!e.getValue()) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            Toast.makeText(this,
                "Some permissions denied. Bluetooth/GPS/Camera required.",
                Toast.LENGTH_LONG).show();
        }
    }
}
