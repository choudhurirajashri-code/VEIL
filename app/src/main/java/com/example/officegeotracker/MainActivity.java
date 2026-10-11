package com.example.officegeotracker;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import android.provider.Settings;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * Main Activity that displays the employee's campus presence and floor zone.
 * Typeset and formatted in Swiss International Style (International Typographic Style).
 * 
 * PURE WIFI RSSI MODE — NO GPS:
 *  • Whether the employee is inside/outside the campus
 *  • Which floor/zone they are on (RECEPTION, FLOOR 1, FLOOR 2)
 *  • Real-time signal strength for each campus access point
 *  • Proximity distance estimates based on WiFi RSSI
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // UI Elements
    private TextView tvStatus;
    private TextView tvCurrentZone;
    private TextView tvNearestAp;
    private TextView tvConfidence;
    private TextView tvWifiScore;
    private TextView tvMeshAPs;
    private TextView tvAvgSignal;
    private TextView tvNode1Signal;
    private ProgressBar progressNode1;
    private TextView tvNode2Signal;
    private ProgressBar progressNode2;
    private TextView tvNode3Signal;
    private ProgressBar progressNode3;
    private ProgressBar progressConfidence;
    private ProgressBar progressWifi;
    private Button btnToggleTracking;
    private LinearLayout layoutDetails;
    private CardView cardLocationWarning;
    private Button btnEnableLocation;

    private boolean isTracking = false;

    // Broadcast receiver for updates from GeoFenceTrackingService
    private final BroadcastReceiver geoFenceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (GeoFenceTrackingService.ACTION_GEOFENCE_UPDATE.equals(intent.getAction())) {
                updateUI(intent);
            }
        }
    };

    // Permission launcher for WiFi scanning permissions
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        boolean allGranted = true;
                        for (Boolean granted : result.values()) {
                            if (!granted) {
                                allGranted = false;
                                break;
                            }
                        }
                        if (allGranted) {
                            startTracking();
                        } else {
                            Toast.makeText(this,
                                    "WiFi scanning permissions required for campus tracking",
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupClickListeners();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkLocationServiceState();
        IntentFilter filter = new IntentFilter(GeoFenceTrackingService.ACTION_GEOFENCE_UPDATE);
        LocalBroadcastManager.getInstance(this).registerReceiver(geoFenceReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(geoFenceReceiver);
    }

    private void checkLocationServiceState() {
        boolean enabled = GeoFenceTrackingService.isLocationServiceEnabled(this);
        if (cardLocationWarning != null) {
            cardLocationWarning.setVisibility(enabled ? View.GONE : View.VISIBLE);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  UI INITIALIZATION
    // ═══════════════════════════════════════════════════════════════

    private void initViews() {
        tvStatus           = findViewById(R.id.tv_status);
        tvCurrentZone      = findViewById(R.id.tv_current_zone);
        tvNearestAp        = findViewById(R.id.tv_nearest_ap);
        tvConfidence       = findViewById(R.id.tv_confidence);
        tvWifiScore        = findViewById(R.id.tv_wifi_score);
        tvMeshAPs          = findViewById(R.id.tv_mesh_aps);
        tvAvgSignal        = findViewById(R.id.tv_avg_signal);
        tvNode1Signal      = findViewById(R.id.tv_node1_signal);
        progressNode1      = findViewById(R.id.progress_node1);
        tvNode2Signal      = findViewById(R.id.tv_node2_signal);
        progressNode2      = findViewById(R.id.progress_node2);
        tvNode3Signal      = findViewById(R.id.tv_node3_signal);
        progressNode3      = findViewById(R.id.progress_node3);
        progressConfidence = findViewById(R.id.progress_confidence);
        progressWifi       = findViewById(R.id.progress_wifi);
        btnToggleTracking  = findViewById(R.id.btn_toggle_tracking);
        layoutDetails      = findViewById(R.id.layout_details);
        cardLocationWarning = findViewById(R.id.card_location_warning);
        btnEnableLocation  = findViewById(R.id.btn_enable_location);
    }

    private void setupClickListeners() {
        btnToggleTracking.setOnClickListener(v -> {
            if (isTracking) {
                stopTracking();
            } else {
                checkPermissionsAndStart();
            }
        });

        if (btnEnableLocation != null) {
            btnEnableLocation.setOnClickListener(v -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Please open Settings > Location and turn it ON", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  PERMISSION HANDLING (WiFi SCANNING ONLY — NO GPS)
    // ═══════════════════════════════════════════════════════════════

    private void checkPermissionsAndStart() {
        String[] permissions;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.FOREGROUND_SERVICE
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.FOREGROUND_SERVICE
            };
        }

        boolean allGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            checkLocationServiceState();
            if (!GeoFenceTrackingService.isLocationServiceEnabled(this)) {
                Toast.makeText(this,
                        "Please turn ON Location in phone settings to allow Wi-Fi scanning (no GPS used)",
                        Toast.LENGTH_LONG).show();
            }
            startTracking();
        } else {
            permissionLauncher.launch(permissions);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  SERVICE CONTROL
    // ═══════════════════════════════════════════════════════════════

    private void startTracking() {
        Intent serviceIntent = new Intent(this, GeoFenceTrackingService.class);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        isTracking = true;
        btnToggleTracking.setText("DISENGAGE MONITORING  ■");
        btnToggleTracking.setBackgroundResource(R.drawable.bg_swiss_button_stop);
        tvStatus.setText("INITIALIZING SCAN...");
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.swiss_black));
        if (tvCurrentZone != null) {
            tvCurrentZone.setText("ZONE: ACQUIRING BEACON...");
            tvCurrentZone.setTextColor(ContextCompat.getColor(this, R.color.swiss_muted));
        }
        if (tvNearestAp != null) {
            tvNearestAp.setText("Nearest Node: Scanning campus frequencies...");
            tvNearestAp.setTextColor(ContextCompat.getColor(this, R.color.swiss_muted));
        }
        layoutDetails.setVisibility(View.VISIBLE);

        Toast.makeText(this, "WiFi tracking engaged", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "WiFi tracking started");
    }

    private void stopTracking() {
        Intent serviceIntent = new Intent(this, GeoFenceTrackingService.class);
        stopService(serviceIntent);

        isTracking = false;
        btnToggleTracking.setText("START WI-FI MONITORING  →");
        btnToggleTracking.setBackgroundResource(R.drawable.bg_swiss_button_start);

        tvStatus.setText("SYSTEM INACTIVE");
        tvStatus.setTextColor(ContextCompat.getColor(this, R.color.swiss_black));
        if (tvCurrentZone != null) {
            tvCurrentZone.setText("ZONE: --");
            tvCurrentZone.setTextColor(ContextCompat.getColor(this, R.color.swiss_muted));
        }
        if (tvNearestAp != null) {
            tvNearestAp.setText("Nearest Node: Standby");
            tvNearestAp.setTextColor(ContextCompat.getColor(this, R.color.swiss_muted));
        }
        layoutDetails.setVisibility(View.GONE);

        Toast.makeText(this, "WiFi tracking disengaged", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "Tracking stopped");
    }

    // ═══════════════════════════════════════════════════════════════
    //  UI UPDATE FROM BROADCAST
    // ═══════════════════════════════════════════════════════════════

    private void updateUI(Intent intent) {
        boolean isInside      = intent.getBooleanExtra(
                GeoFenceTrackingService.EXTRA_IS_INSIDE, false);
        float confidence      = intent.getFloatExtra(
                GeoFenceTrackingService.EXTRA_CONFIDENCE, 0f);
        int meshAPCount       = intent.getIntExtra(
                GeoFenceTrackingService.EXTRA_MESH_AP_COUNT, 0);
        int avgSignal         = intent.getIntExtra(
                GeoFenceTrackingService.EXTRA_AVG_SIGNAL, -100);

        String currentZone    = intent.getStringExtra(
                GeoFenceTrackingService.EXTRA_CURRENT_ZONE);
        String nearestSsid    = intent.getStringExtra(
                GeoFenceTrackingService.EXTRA_NEAREST_SSID);
        int nearestSignal     = intent.getIntExtra(
                GeoFenceTrackingService.EXTRA_NEAREST_SIGNAL, -100);
        float estDist         = intent.getFloatExtra(
                GeoFenceTrackingService.EXTRA_EST_DISTANCE, 999f);

        int node1Signal       = intent.getIntExtra(
                GeoFenceTrackingService.EXTRA_NODE1_SIGNAL, -100);
        int node2Signal       = intent.getIntExtra(
                GeoFenceTrackingService.EXTRA_NODE2_SIGNAL, -100);
        int node3Signal       = intent.getIntExtra(
                GeoFenceTrackingService.EXTRA_NODE3_SIGNAL, -100);
        boolean locationEnabled = intent.getBooleanExtra(
                GeoFenceTrackingService.EXTRA_LOCATION_SERVICE_ENABLED, true);

        if (!locationEnabled) {
            if (cardLocationWarning != null) cardLocationWarning.setVisibility(View.VISIBLE);
        } else {
            if (cardLocationWarning != null) cardLocationWarning.setVisibility(View.GONE);
        }

        // ─── Status & Zone ───────────────────────────────────────
        if (isInside) {
            tvStatus.setText("INSIDE CAMPUS");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.swiss_black));
            if (tvCurrentZone != null) {
                tvCurrentZone.setText("ZONE: " + (currentZone != null ? currentZone.toUpperCase() : "CAMPUS"));
                tvCurrentZone.setTextColor(ContextCompat.getColor(this, R.color.swiss_black));
            }
        } else if (!locationEnabled && meshAPCount == 0) {
            tvStatus.setText("LOCATION SWITCH OFF");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.swiss_red));
            if (tvCurrentZone != null) {
                tvCurrentZone.setText("SCANNING BLOCKED BY OS");
                tvCurrentZone.setTextColor(ContextCompat.getColor(this, R.color.swiss_red));
            }
        } else {
            tvStatus.setText("OUTSIDE PERIMETER");
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.swiss_red));
            if (tvCurrentZone != null) {
                tvCurrentZone.setText("ZONE: OUTSIDE BOUNDARY");
                tvCurrentZone.setTextColor(ContextCompat.getColor(this, R.color.swiss_red));
            }
        }

        if (nearestSignal > -100 && nearestSsid != null) {
            tvNearestAp.setText("Nearest Node: " + nearestSsid + " (" + nearestSignal + " dBm • ~" +
                    String.format("%.1f", estDist) + "m)");
            tvNearestAp.setTextColor(ContextCompat.getColor(this, R.color.swiss_charcoal));
        } else if (!locationEnabled && meshAPCount == 0) {
            tvNearestAp.setText("Wi-Fi scanning is restricted while system Location is OFF");
            tvNearestAp.setTextColor(ContextCompat.getColor(this, R.color.swiss_red));
        } else {
            tvNearestAp.setText("Nearest Node: No campus beacon detected");
            tvNearestAp.setTextColor(ContextCompat.getColor(this, R.color.swiss_muted));
        }

        // ─── Confidence ──────────────────────────────────────────
        int confidencePercent = Math.round(confidence * 100);
        tvConfidence.setText(confidencePercent + "%");
        progressConfidence.setProgress(confidencePercent);

        // ─── WiFi Score ──────────────────────────────────────────
        tvWifiScore.setText("WiFi RSSI Score: " + confidencePercent + "%");
        progressWifi.setProgress(confidencePercent);

        // ─── Details ─────────────────────────────────────────────
        tvMeshAPs.setText(String.format("%02d / %02d",
                meshAPCount, GeoFenceConfig.CAMPUS_NODES.length));
        tvAvgSignal.setText(avgSignal + " dBm");

        // ─── Floor Nodes Signal Breakdown ─────────────────────────
        updateNodeView(tvNode1Signal, progressNode1, "58:BF:25:92:F3:41", node1Signal);
        updateNodeView(tvNode2Signal, progressNode2, "88:13:BF:09:D6:55", node2Signal);
        updateNodeView(tvNode3Signal, progressNode3, "8C:94:DF:6D:73:81", node3Signal);
    }

    private void updateNodeView(TextView tv, ProgressBar pb, String bssid, int rssiDbm) {
        if (tv == null || pb == null) return;
        if (rssiDbm > -100) {
            int quality = Math.max(0, Math.min(100, 2 * (rssiDbm + 100)));
            tv.setText("Signal: " + rssiDbm + " dBm (" + quality + "% • " + bssid + ")");
            tv.setTextColor(ContextCompat.getColor(this, R.color.swiss_black));
            pb.setProgress(quality);
        } else {
            tv.setText("Signal: Not detected (" + bssid + ")");
            tv.setTextColor(ContextCompat.getColor(this, R.color.swiss_muted));
            pb.setProgress(0);
        }
    }
}
