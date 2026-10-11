package com.example.officegeotracker;

/**
 * Configuration class for the office campus WiFi RSSI localization.
 * 
 * Uses purely WiFi signal scanning (RSSI) to determine whether an employee
 * is inside the campus and which floor/zone they are located on.
 * NO GPS is used.
 */
public class GeoFenceConfig {

    // ═══════════════════════════════════════════════════════════════
    //  CAMPUS WIFI NODES (INDOOR POSITIONING & SCAN-ONLY DETECTION)
    //  Access points located across the campus floors.
    // ═══════════════════════════════════════════════════════════════

    public static class CampusNode {
        public final int nodeId;
        public final String zoneName;
        public final String ssid;
        public final String ipAddress;
        public final String bssid;

        public CampusNode(int nodeId, String zoneName, String ssid, String ipAddress, String bssid) {
            this.nodeId    = nodeId;
            this.zoneName  = zoneName;
            this.ssid      = ssid;
            this.ipAddress = ipAddress;
            this.bssid     = bssid.toUpperCase();
        }
    }

    public static final CampusNode[] CAMPUS_NODES = {
        new CampusNode(1, "RECEPTION", "OFFICE_RECEPTION", "192.168.4.1", "58:BF:25:92:F3:41"),
        new CampusNode(2, "FLOOR_1",   "OFFICE_FLOOR1",    "192.168.4.1", "88:13:BF:09:D6:55"),
        new CampusNode(3, "FLOOR_2",   "OFFICE_FLOOR2",    "192.168.4.1", "8C:94:DF:6D:73:81")
    };

    /**
     * BSSIDs of the campus access points.
     */
    public static final String[] OFFICE_MESH_BSSIDS = {
        "58:BF:25:92:F3:41",  // Node 1: RECEPTION
        "88:13:BF:09:D6:55",  // Node 2: FLOOR_1
        "8C:94:DF:6D:73:81"   // Node 3: FLOOR_2
    };

    /**
     * SSIDs of the campus access points.
     */
    public static final String[] OFFICE_MESH_SSIDS = {
        "OFFICE_RECEPTION",
        "OFFICE_FLOOR1",
        "OFFICE_FLOOR2"
    };

    /**
     * Helper to match a scanned network to its designated campus node.
     */
    public static CampusNode findNodeByBssidOrSsid(String bssid, String ssid) {
        if (bssid != null && !bssid.isEmpty() && !bssid.equals("02:00:00:00:00:00")) {
            String upper = bssid.toUpperCase().trim();
            for (CampusNode node : CAMPUS_NODES) {
                if (node.bssid.equalsIgnoreCase(upper)) {
                    return node;
                }
            }
        }
        if (ssid != null && !ssid.isEmpty() && !ssid.equals("<unknown ssid>")) {
            String cleanSsid = ssid.replace("\"", "").trim();
            for (CampusNode node : CAMPUS_NODES) {
                if (node.ssid.equalsIgnoreCase(cleanSsid)) {
                    return node;
                }
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  THRESHOLDS & WEIGHTS (PURE WIFI RSSI)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Minimum WiFi signal strength (in dBm) to consider a campus AP as "reachable."
     */
    public static final int MIN_WIFI_SIGNAL_STRENGTH_DBM = -85;

    /**
     * Minimum number of campus APs that must be visible for WiFi presence.
     */
    public static final int MIN_VISIBLE_MESH_APS = 1;

    /**
     * WiFi signal strength (dBm) at which an AP is considered strong / close proximity.
     */
    public static final int STRONG_SIGNAL_THRESHOLD_DBM = -55;

    /**
     * Minimum confidence score (0.0 – 1.0) required to declare the employee as inside.
     */
    public static final float CONFIDENCE_THRESHOLD = 0.5f;

    // ═══════════════════════════════════════════════════════════════
    //  TRACKING INTERVALS
    // ═══════════════════════════════════════════════════════════════
    public static final long WIFI_SCAN_INTERVAL_MS        = 15_000;  // 15 seconds
    public static final long GEOFENCE_CHECK_INTERVAL_MS   = 10_000;  // 10 seconds
}
