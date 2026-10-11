package com.example.officegeotracker;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.util.List;

/**
 * Foreground Service that continuously monitors the employee's location using
 * GPS and WiFi mesh signals, and broadcasts whether they are inside the
 * geo-fenced office campus.
 * 
 * Runs as a foreground service (required for Android 8+ background location).
 * 
 * ARCHITECTURE:
 * ─────────────
 *   [GPS via FusedLocationProvider]  ──┐
 *                                      ├──→ GeoFenceChecker → Broadcast result
 *   [WiFi mesh scan via WifiManager] ──┘
 *                                         ↓
 *                                   MainActivity receives
 *                                   LocalBroadcast & updates UI
 */
public class GeoFenceTrackingService extends Service {

    private static final String TAG = "GeoFenceTracker";
    public static final String ACTION_GEOFENCE_UPDATE =
            "com.example.officegeotracker.GEOFENCE_UPDATE";
    public static final String EXTRA_IS_INSIDE    = "extra_is_inside";
    public static final String EXTRA_CONFIDENCE   = "extra_confidence";
    public static final String EXTRA_GPS_SCORE    = "extra_gps_score";
    public static final String EXTRA_WIFI_SCORE   = "extra_wifi_score";
    public static final String EXTRA_DISTANCE     = "extra_distance";
    public static final String EXTRA_MESH_AP_COUNT = "extra_mesh_ap_count";
    public static final String EXTRA_AVG_SIGNAL    = "extra_avg_signal";

    private static final String CHANNEL_ID = "geo_fence_tracking_channel";
    private static final int NOTIFICATION_ID = 1001;

    // Components
    private GeoFenceChecker geoFenceChecker;
    private FusedLocationProviderClient fusedLocationClient;
    private LocationCallback locationCallback;
    private WifiManager wifiManager;
    private Handler mainHandler;

    // Current state
    private Location currentLocation;
    private List<ScanResult> latestScanResults;

    // WiFi scan receiver
    private final BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean success = intent.getBooleanExtra(
                    WifiManager.EXTRA_RESULTS_UPDATED, false);
            if (success) {
                latestScanResults = wifiManager.getScanResults();
                Log.d(TAG, "WiFi scan completed. Found " +
                        (latestScanResults != null ? latestScanResults.size() : 0) + " networks");
            } else {
                // Use cached results if scan failed
                latestScanResults = wifiManager.getScanResults();
                Log.w(TAG, "WiFi scan failed, using cached results");
            }
        }
    };

    // Periodic geo-fence check runnable
    private final Runnable geoFenceCheckRunnable = new Runnable() {
        @Override
        public void run() {
            performGeoFenceCheck();
            mainHandler.postDelayed(this, GeoFenceConfig.GEOFENCE_CHECK_INTERVAL_MS);
        }
    };

    // Periodic WiFi scan trigger
    private final Runnable wifiScanRunnable = new Runnable() {
        @Override
        public void run() {
            triggerWifiScan();
            mainHandler.postDelayed(this, GeoFenceConfig.WIFI_SCAN_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "GeoFenceTrackingService created");

        geoFenceChecker     = new GeoFenceChecker();
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        wifiManager         = (WifiManager) getApplicationContext()
                                    .getSystemService(Context.WIFI_SERVICE);
        mainHandler         = new Handler(Looper.getMainLooper());

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "GeoFenceTrackingService started");

        // Start as foreground service
        Notification notification = buildForegroundNotification("Monitoring campus presence...");
        startForeground(NOTIFICATION_ID, notification);

        // Start GPS tracking
        startLocationUpdates();

        // Register WiFi scan receiver and start periodic scans
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        registerReceiver(wifiScanReceiver, filter);
        mainHandler.post(wifiScanRunnable);

        // Start periodic geo-fence checks
        mainHandler.postDelayed(geoFenceCheckRunnable, 2000); // Initial delay 2s

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "GeoFenceTrackingService destroyed");

        // Clean up
        mainHandler.removeCallbacks(geoFenceCheckRunnable);
        mainHandler.removeCallbacks(wifiScanRunnable);

        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        try {
            unregisterReceiver(wifiScanReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }

    // ═══════════════════════════════════════════════════════════════
    //  GPS LOCATION UPDATES
    // ═══════════════════════════════════════════════════════════════

    private void startLocationUpdates() {
        LocationRequest locationRequest = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                GeoFenceConfig.GPS_UPDATE_INTERVAL_MS
        )
                .setMinUpdateIntervalMillis(GeoFenceConfig.GPS_FASTEST_INTERVAL_MS)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult != null && locationResult.getLastLocation() != null) {
                    currentLocation = locationResult.getLastLocation();
                    Log.d(TAG, "GPS updated: " + currentLocation.getLatitude()
                            + ", " + currentLocation.getLongitude()
                            + " (accuracy: " + currentLocation.getAccuracy() + "m)");
                }
            }
        };

        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(
                    locationRequest, locationCallback, Looper.getMainLooper());
        } else {
            Log.e(TAG, "Missing ACCESS_FINE_LOCATION permission!");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  WIFI MESH SCANNING
    // ═══════════════════════════════════════════════════════════════

    @SuppressWarnings("deprecation")
    private void triggerWifiScan() {
        if (wifiManager != null && wifiManager.isWifiEnabled()) {
            boolean started = wifiManager.startScan();
            if (!started) {
                Log.w(TAG, "WiFi scan throttled by OS, using cached results");
                latestScanResults = wifiManager.getScanResults();
            }
        } else {
            Log.w(TAG, "WiFi is disabled");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GEO-FENCE CHECK (THE CORE CONDITIONAL EVALUATION)
    // ═══════════════════════════════════════════════════════════════

    private void performGeoFenceCheck() {
        // ★ INVOKE THE GEO-FENCE CHECKER (SCAN-ONLY) ★
        // No WiFi connection info needed — purely scan-based detection
        GeoFenceChecker.GeoFenceResult result = geoFenceChecker.checkIfInsideGeofence(
                currentLocation,
                latestScanResults
        );

        // Log the result
        Log.i(TAG, "─────────────────────────────────────");
        Log.i(TAG, "GeoFence Check Result:");
        Log.i(TAG, "  Inside Campus:  " + result.isInsideCampus);
        Log.i(TAG, "  Confidence:     " + String.format("%.2f", result.confidenceScore));
        Log.i(TAG, "  GPS Score:      " + String.format("%.2f", result.gpsScore));
        Log.i(TAG, "  WiFi Score:     " + String.format("%.2f", result.wifiScore));
        Log.i(TAG, "  Distance:       " + String.format("%.1fm", result.distanceFromCenter));
        Log.i(TAG, "  Mesh APs:       " + result.visibleMeshAPCount);
        Log.i(TAG, "  Avg Signal:     " + result.averageSignalStrength + " dBm");
        Log.i(TAG, "─────────────────────────────────────");

        // Broadcast result to the Activity
        broadcastResult(result);

        // Update foreground notification
        String status = result.isInsideCampus
                ? "✅ Inside campus (confidence: " + String.format("%.0f%%", result.confidenceScore * 100) + ")"
                : "❌ Outside campus (distance: " + String.format("%.0fm", result.distanceFromCenter) + ")";

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildForegroundNotification(status));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  BROADCAST RESULT TO ACTIVITY
    // ═══════════════════════════════════════════════════════════════

    private void broadcastResult(GeoFenceChecker.GeoFenceResult result) {
        Intent intent = new Intent(ACTION_GEOFENCE_UPDATE);
        intent.putExtra(EXTRA_IS_INSIDE,          result.isInsideCampus);
        intent.putExtra(EXTRA_CONFIDENCE,          result.confidenceScore);
        intent.putExtra(EXTRA_GPS_SCORE,           result.gpsScore);
        intent.putExtra(EXTRA_WIFI_SCORE,          result.wifiScore);
        intent.putExtra(EXTRA_DISTANCE,            result.distanceFromCenter);
        intent.putExtra(EXTRA_MESH_AP_COUNT,       result.visibleMeshAPCount);
        intent.putExtra(EXTRA_AVG_SIGNAL,          result.averageSignalStrength);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // ═══════════════════════════════════════════════════════════════
    //  NOTIFICATION HELPERS
    // ═══════════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Geo-Fence Tracking",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitors employee campus presence using GPS and WiFi");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildForegroundNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Office Campus Tracker")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }
}
