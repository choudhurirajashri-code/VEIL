package com.example.officegeotracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TransportInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.List;

/**
 * Foreground Service that continuously monitors the employee's campus presence
 * and floor location using pure WiFi RSSI scan data.
 * 
 * NO GPS is used.
 */
public class GeoFenceTrackingService extends Service {

    private static final String TAG = "GeoFenceTracker";
    public static final String ACTION_GEOFENCE_UPDATE =
            "com.example.officegeotracker.GEOFENCE_UPDATE";

    public static final String EXTRA_IS_INSIDE       = "extra_is_inside";
    public static final String EXTRA_CONFIDENCE      = "extra_confidence";
    public static final String EXTRA_MESH_AP_COUNT   = "extra_mesh_ap_count";
    public static final String EXTRA_AVG_SIGNAL      = "extra_avg_signal";

    // Campus Zone Extras
    public static final String EXTRA_CURRENT_ZONE    = "extra_current_zone";
    public static final String EXTRA_NEAREST_SSID    = "extra_nearest_ssid";
    public static final String EXTRA_NEAREST_BSSID   = "extra_nearest_bssid";
    public static final String EXTRA_NEAREST_SIGNAL  = "extra_nearest_signal";
    public static final String EXTRA_EST_DISTANCE    = "extra_est_distance";
    public static final String EXTRA_NODE1_SIGNAL    = "extra_node1_signal";
    public static final String EXTRA_NODE2_SIGNAL    = "extra_node2_signal";
    public static final String EXTRA_NODE3_SIGNAL    = "extra_node3_signal";
    public static final String EXTRA_LOCATION_SERVICE_ENABLED = "extra_location_service_enabled";

    private static final String CHANNEL_ID = "geo_fence_tracking_channel";
    private static final int NOTIFICATION_ID = 1001;

    // Components
    private GeoFenceChecker geoFenceChecker;
    private WifiManager wifiManager;
    private Handler mainHandler;

    // Current state
    private List<ScanResult> latestScanResults;

    // WiFi scan receiver
    private final BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean success = intent.getBooleanExtra(
                    WifiManager.EXTRA_RESULTS_UPDATED, false);
            try {
                latestScanResults = wifiManager.getScanResults();
                Log.d(TAG, "WiFi scan completed. Found " +
                        (latestScanResults != null ? latestScanResults.size() : 0) + " networks");
            } catch (SecurityException se) {
                Log.w(TAG, "SecurityException retrieving scan results: " + se.getMessage());
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
        Log.i(TAG, "GeoFenceTrackingService created (Pure WiFi RSSI Mode)");

        geoFenceChecker = new GeoFenceChecker();
        wifiManager     = (WifiManager) getApplicationContext()
                                    .getSystemService(Context.WIFI_SERVICE);
        mainHandler     = new Handler(Looper.getMainLooper());

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "GeoFenceTrackingService started");

        // Start as foreground service
        Notification notification = buildForegroundNotification("Monitoring campus WiFi presence...");
        startForeground(NOTIFICATION_ID, notification);

        // Register WiFi scan receiver and start periodic scans
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        registerReceiver(wifiScanReceiver, filter);
        mainHandler.post(wifiScanRunnable);

        // Start periodic presence checks
        mainHandler.postDelayed(geoFenceCheckRunnable, 2000); // Initial delay 2s

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "GeoFenceTrackingService destroyed");

        mainHandler.removeCallbacks(geoFenceCheckRunnable);
        mainHandler.removeCallbacks(wifiScanRunnable);

        try {
            unregisterReceiver(wifiScanReceiver);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  LOCATION SERVICE & CONNECTED WIFI HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Checks whether system-level location toggle is enabled.
     * Note: Android OS requires this switch to be ON for WifiManager.getScanResults().
     * (NO GPS coordinates are requested or used).
     */
    public static boolean isLocationServiceEnabled(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        } else {
            try {
                int mode = Settings.Secure.getInt(
                        context.getContentResolver(),
                        Settings.Secure.LOCATION_MODE,
                        Settings.Secure.LOCATION_MODE_OFF);
                return mode != Settings.Secure.LOCATION_MODE_OFF;
            } catch (Exception e) {
                return true;
            }
        }
    }

    /**
     * Obtains the currently connected WiFi network info (SSID, BSSID, RSSI).
     * Acts as an immediate zero-latency fallback when active scanning is throttled or warming up.
     */
    private WifiInfo getConnectedWifiInfo() {
        if (wifiManager == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    Network activeNet = cm.getActiveNetwork();
                    if (activeNet != null) {
                        NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                            TransportInfo transportInfo = caps.getTransportInfo();
                            if (transportInfo instanceof WifiInfo) {
                                return (WifiInfo) transportInfo;
                            }
                        }
                    }
                }
            }
            return wifiManager.getConnectionInfo();
        } catch (Exception e) {
            Log.w(TAG, "Error querying connected WiFi: " + e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  WIFI SCAN TRIGGER
    // ═══════════════════════════════════════════════════════════════

    private void triggerWifiScan() {
        if (wifiManager == null) return;

        boolean locationOn = isLocationServiceEnabled(this);
        if (!locationOn) {
            Log.w(TAG, "⚠️ System Location switch is OFF! Android OS blocks WifiManager.getScanResults().");
        }

        if (wifiManager.isWifiEnabled()) {
            boolean initiated = wifiManager.startScan();
            if (!initiated) {
                Log.w(TAG, "WiFi scan throttled by OS, using cached results");
                try {
                    latestScanResults = wifiManager.getScanResults();
                } catch (SecurityException se) {
                    Log.w(TAG, "SecurityException: " + se.getMessage());
                }
            }
        } else {
            Log.w(TAG, "WiFi is disabled");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  GEO-FENCE / ZONE CHECK (PURE WIFI RSSI)
    // ═══════════════════════════════════════════════════════════════

    private void performGeoFenceCheck() {
        boolean locationEnabled = isLocationServiceEnabled(this);

        // Fetch currently connected WiFi network info as immediate presence source
        WifiInfo connectedInfo = getConnectedWifiInfo();
        String connSsid = null;
        String connBssid = null;
        int connRssi = -100;
        int connFreq = 0;

        if (connectedInfo != null) {
            connSsid = connectedInfo.getSSID();
            connBssid = connectedInfo.getBSSID();
            connRssi = connectedInfo.getRssi();
            connFreq = connectedInfo.getFrequency();
            Log.d(TAG, "Connected WiFi: SSID=" + connSsid + ", BSSID=" + connBssid + ", RSSI=" + connRssi + " dBm");
        }

        GeoFenceChecker.GeoFenceResult result = geoFenceChecker.checkIfInsideGeofence(
                latestScanResults, connSsid, connBssid, connRssi, connFreq
        );

        Log.i(TAG, "─────────────────────────────────────");
        Log.i(TAG, "WiFi Presence Result:");
        Log.i(TAG, "  Inside Campus:  " + result.isInsideCampus);
        Log.i(TAG, "  Confidence:     " + String.format("%.2f", result.confidenceScore));
        Log.i(TAG, "  Campus APs:     " + result.visibleMeshAPCount);
        Log.i(TAG, "  Avg Signal:     " + result.averageSignalStrength + " dBm");
        Log.i(TAG, "  Zone/Location:  " + result.currentZone);
        Log.i(TAG, "  Nearest AP:     " + result.nearestNodeSsid + " (" + result.nearestNodeSignal + " dBm, ~" +
                String.format("%.1fm", result.estimatedDistanceToNode) + ")");
        Log.i(TAG, "  Location Toggle:" + (locationEnabled ? " ON" : " OFF (Android scan blocked!)"));
        Log.i(TAG, "─────────────────────────────────────");

        broadcastResult(result, locationEnabled);

        String status;
        if (result.isInsideCampus) {
            status = "✅ Campus: " + result.currentZone + " (" + result.nearestNodeSignal + " dBm)";
        } else if (!locationEnabled) {
            status = "⚠️ Turn ON Location in phone settings to scan Wi-Fi";
        } else {
            status = "❌ Outside campus (No office WiFi detected)";
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildForegroundNotification(status));
        }
    }

    private void broadcastResult(GeoFenceChecker.GeoFenceResult result, boolean locationEnabled) {
        Intent intent = new Intent(ACTION_GEOFENCE_UPDATE);
        intent.putExtra(EXTRA_IS_INSIDE,                  result.isInsideCampus);
        intent.putExtra(EXTRA_CONFIDENCE,                 result.confidenceScore);
        intent.putExtra(EXTRA_MESH_AP_COUNT,              result.visibleMeshAPCount);
        intent.putExtra(EXTRA_AVG_SIGNAL,                 result.averageSignalStrength);

        intent.putExtra(EXTRA_CURRENT_ZONE,               result.currentZone);
        intent.putExtra(EXTRA_NEAREST_SSID,               result.nearestNodeSsid);
        intent.putExtra(EXTRA_NEAREST_BSSID,              result.nearestNodeBssid);
        intent.putExtra(EXTRA_NEAREST_SIGNAL,             result.nearestNodeSignal);
        intent.putExtra(EXTRA_EST_DISTANCE,               (float) result.estimatedDistanceToNode);
        intent.putExtra(EXTRA_LOCATION_SERVICE_ENABLED,   locationEnabled);

        int node1 = -100, node2 = -100, node3 = -100;
        for (GeoFenceChecker.MeshAPInfo ap : result.detectedMeshAPs) {
            if (ap.nodeId == 1) node1 = ap.signalStrengthDbm;
            else if (ap.nodeId == 2) node2 = ap.signalStrengthDbm;
            else if (ap.nodeId == 3) node3 = ap.signalStrengthDbm;
        }
        intent.putExtra(EXTRA_NODE1_SIGNAL, node1);
        intent.putExtra(EXTRA_NODE2_SIGNAL, node2);
        intent.putExtra(EXTRA_NODE3_SIGNAL, node3);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    // ═══════════════════════════════════════════════════════════════
    //  NOTIFICATION HELPERS
    // ═══════════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Campus Presence Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Monitors employee campus presence via WiFi RSSI");
            channel.setShowBadge(false);

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
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Office Campus Tracker")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}
