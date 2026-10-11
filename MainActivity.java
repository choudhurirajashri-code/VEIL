package com.example.officegeotracker;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

/**
 * Main Activity that displays the employee's campus presence status.
 * 
 * Shows:
 *  • Whether the employee is inside/outside the geo-fenced campus
 *  • Confidence score (combined GPS + WiFi)
 *  • Individual GPS and WiFi scores
 *  • Distance from campus center
 *  • Number of visible mesh WiFi access points
 *  • Connection status to the mesh network
 * 
 * Controls:
 *  • Start/Stop tracking button
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // UI Elements
    private TextView tvStatus;
    private TextView tvConfidence;
    private TextView tvGpsScore;
    private TextView tvWifiScore;
    private TextView tvDistance;
    private TextView tvMeshAPs;
    private TextView tvAvgSignal;
    private ProgressBar progressConfidence;
    private ProgressBar progressGps;
    private ProgressBar progressWifi;
    private Button btnToggleTracking;
    private LinearLayout layoutDetails;

    private boolean isTracking = false;

    // Broadcast receiver for geo-fence updates from the Service
    private final BroadcastReceiver geoFenceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (GeoFenceTrackingService.ACTION_GEOFENCE_UPDATE.equals(intent.getAction())) {
                updateUI(intent);
            }
        }
    };

    // Permission launcher
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
                                    "Permissions required for campus tracking",
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
        LocalBroadcastManager.getInstance(this).registerReceiver(
                geoFenceReceiver,
                new IntentFilter(GeoFenceTrackingService.ACTION_GEOFENCE_UPDATE)
        );
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(geoFenceReceiver);
    }

    // ═══════════════════════════════════════════════════════════════
    //  UI INITIALIZATION
    // ═══════════════════════════════════════════════════════════════

    private void initViews() {
        tvStatus           = findViewById(R.id.tv_status);
        tvConfidence       = findViewById(R.id.tv_confidence);
        tvGpsScore         = findViewById(R.id.tv_gps_score);
        tvWifiScore        = findViewById(R.id.tv_wifi_score);
        tvDistance          = findViewById(R.id.tv_distance);
        tvMeshAPs          = findViewById(R.id.tv_mesh_aps);
        tvAvgSignal        = findViewById(R.id.tv_avg_signal);
        progressConfidence = findViewById(R.id.progress_confidence);
        progressGps        = findViewById(R.id.progress_gps);
        progressWifi       = findViewById(R.id.progress_wifi);
        btnToggleTracking  = findViewById(R.id.btn_toggle_tracking);
        layoutDetails      = findViewById(R.id.layout_details);
    }

    private void setupClickListeners() {
        btnToggleTracking.setOnClickListener(v -> {
            if (isTracking) {
                stopTracking();
            } else {
                checkPermissionsAndStart();
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════
    //  PERMISSION HANDLING
    // ═══════════════════════════════════════════════════════════════

    private void checkPermissionsAndStart() {
        String[] permissions;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.FOREGROUND_SERVICE
            };
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.FOREGROUND_SERVICE
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
            };
        }

        boolean allGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
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
        btnToggleTracking.setText("⏹ Stop Tracking");
        btnToggleTracking.setBackgroundColor(Color.parseColor("#D32F2F"));
        layoutDetails.setVisibility(View.VISIBLE);

        Toast.makeText(this, "Campus tracking started", Toast.LENGTH_SHORT).show();
        Log.i(TAG, "Tracking started");
    }

    private void stopTracking() {
        Intent serviceIntent = new Intent(this, GeoFenceTrackingService.class);
        stopService(serviceIntent);

        isTracking = false;
        btnToggleTracking.setText("▶ Start Tracking");
        btnToggleTracking.setBackgroundColor(Color.parseColor("#388E3C"));

        tvStatus.setText("Tracking Stopped");
        tvStatus.setTextColor(Color.GRAY);
        layoutDetails.setVisibility(View.GONE);

        Toast.makeText(this, "Campus tracking stopped", Toast.LENGTH_SHORT).show();
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
        float gpsScore        = intent.getFloatExtra(
                GeoFenceTrackingService.EXTRA_GPS_SCORE, 0f);
        float wifiScore       = intent.getFloatExtra(
                GeoFenceTrackingService.EXTRA_WIFI_SCORE, 0f);
        float distance        = intent.getFloatExtra(
                GeoFenceTrackingService.EXTRA_DISTANCE, 0f);
        int meshAPCount       = intent.getIntExtra(
                GeoFenceTrackingService.EXTRA_MESH_AP_COUNT, 0);
        int avgSignal         = intent.getIntExtra(
                GeoFenceTrackingService.EXTRA_AVG_SIGNAL, -100);

        // ─── Status ──────────────────────────────────────────────
        if (isInside) {
            tvStatus.setText("✅ INSIDE CAMPUS");
            tvStatus.setTextColor(Color.parseColor("#2E7D32")); // Green
        } else {
            tvStatus.setText("❌ OUTSIDE CAMPUS");
            tvStatus.setTextColor(Color.parseColor("#C62828")); // Red
        }

        // ─── Confidence ──────────────────────────────────────────
        int confidencePercent = Math.round(confidence * 100);
        tvConfidence.setText("Combined Confidence: " + confidencePercent + "%");
        progressConfidence.setProgress(confidencePercent);

        // ─── GPS Score ───────────────────────────────────────────
        int gpsPercent = Math.round(gpsScore * 100);
        tvGpsScore.setText("GPS Score: " + gpsPercent + "%");
        progressGps.setProgress(gpsPercent);

        // ─── WiFi Score ──────────────────────────────────────────
        int wifiPercent = Math.round(wifiScore * 100);
        tvWifiScore.setText("WiFi Mesh Score: " + wifiPercent + "%");
        progressWifi.setProgress(wifiPercent);

        // ─── Details ─────────────────────────────────────────────
        tvDistance.setText("Distance from center: " + String.format("%.1f m", distance));
        tvMeshAPs.setText("Visible mesh APs: " + meshAPCount
                + " / " + GeoFenceConfig.OFFICE_MESH_BSSIDS.length);
        tvAvgSignal.setText("Avg signal strength: " + avgSignal + " dBm");
    }
}
