package com.example.officegeotracker;

import android.net.wifi.ScanResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure WiFi RSSI conditional checker that determines whether an employee
 * is inside the campus and which floor/zone they are located on.
 * 
 * NO GPS is used — detection and indoor localization are 100% based on
 * passive scanning of campus WiFi node signal strengths (RSSI).
 */
public class GeoFenceChecker {

    // ─── Result Object ────────────────────────────────────────────
    /**
     * Encapsulates the full result of a campus presence check.
     */
    public static class GeoFenceResult {
        public final boolean isInsideCampus;
        public final float   confidenceScore;     // 0.0 – 1.0 (pure WiFi signal confidence)
        public final int     visibleMeshAPCount;
        public final int     averageSignalStrength; // avg dBm of detected campus APs
        public final List<MeshAPInfo> detectedMeshAPs;

        // Zone localization (where the employee is)
        public final String  currentZone;         // "RECEPTION", "FLOOR_1", "FLOOR_2", "OUTSIDE CAMPUS"
        public final String  nearestNodeSsid;
        public final String  nearestNodeBssid;
        public final int     nearestNodeSignal;   // dBm
        public final double  estimatedDistanceToNode; // meters

        public GeoFenceResult(boolean isInsideCampus, float confidenceScore,
                              int visibleMeshAPCount, int averageSignalStrength,
                              List<MeshAPInfo> detectedMeshAPs, String currentZone,
                              String nearestNodeSsid, String nearestNodeBssid,
                              int nearestNodeSignal, double estimatedDistanceToNode) {
            this.isInsideCampus       = isInsideCampus;
            this.confidenceScore      = confidenceScore;
            this.visibleMeshAPCount   = visibleMeshAPCount;
            this.averageSignalStrength = averageSignalStrength;
            this.detectedMeshAPs      = detectedMeshAPs;
            this.currentZone          = currentZone;
            this.nearestNodeSsid      = nearestNodeSsid;
            this.nearestNodeBssid     = nearestNodeBssid;
            this.nearestNodeSignal    = nearestNodeSignal;
            this.estimatedDistanceToNode = estimatedDistanceToNode;
        }

        @Override
        public String toString() {
            return "GeoFenceResult{" +
                    "isInsideCampus=" + isInsideCampus +
                    ", zone='" + currentZone + '\'' +
                    ", confidence=" + String.format("%.2f", confidenceScore) +
                    ", campusAPs=" + visibleMeshAPCount +
                    ", avgSignal=" + averageSignalStrength + "dBm" +
                    ", nearest=" + nearestNodeSsid + " (" + nearestNodeSignal + "dBm, ~" +
                    String.format("%.1fm", estimatedDistanceToNode) + ")" +
                    '}';
        }
    }

    /**
     * Info about a single detected campus mesh AP.
     */
    public static class MeshAPInfo {
        public final int    nodeId;
        public final String zoneName;
        public final String ssid;
        public final String ipAddress;
        public final String bssid;
        public final int    signalStrengthDbm;
        public final int    frequency;
        public final double estimatedDistance; // meters

        public MeshAPInfo(int nodeId, String zoneName, String ssid, String ipAddress,
                          String bssid, int signalStrengthDbm, int frequency, double estimatedDistance) {
            this.nodeId             = nodeId;
            this.zoneName           = zoneName;
            this.ssid               = ssid;
            this.ipAddress          = ipAddress;
            this.bssid              = bssid;
            this.signalStrengthDbm  = signalStrengthDbm;
            this.frequency          = frequency;
            this.estimatedDistance  = estimatedDistance;
        }
    }

    // ─── Known Mesh BSSIDs & SSIDs (preloaded for O(1) lookup) ──
    private final Set<String> knownMeshBSSIDs;

    public GeoFenceChecker() {
        knownMeshBSSIDs = new HashSet<>();
        for (String bssid : GeoFenceConfig.OFFICE_MESH_BSSIDS) {
            knownMeshBSSIDs.add(bssid.toUpperCase());
        }
    }

    /**
     * Estimates distance in meters from RSSI dBm using indoor log-distance path loss model.
     * Formula: d = 10 ^ ((A - RSSI) / (10 * n))
     * A = -45 dBm (reference RSSI at 1 meter), n = 2.7 (indoor path loss exponent)
     */
    public static double estimateDistance(int rssiDbm) {
        if (rssiDbm == 0) return 999.0;
        double ratio = (-45.0 - rssiDbm) / (10.0 * 2.7);
        double dist = Math.pow(10.0, ratio);
        return Math.round(dist * 10.0) / 10.0;
    }

    // ═══════════════════════════════════════════════════════════════
    //  MAIN CHECK (PURE WIFI RSSI SCAN)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Evaluates whether the employee is inside the office campus
     * purely using WiFi RSSI scan data. No GPS is queried or used.
     * 
     * @param wifiScanResults   Results from WifiManager.getScanResults()
     * @return GeoFenceResult   Full result including confidence score and detected zone
     */
    public GeoFenceResult checkIfInsideGeofence(List<ScanResult> wifiScanResults) {
        return checkIfInsideGeofence(wifiScanResults, null, null, -100, 0);
    }

    /**
     * Evaluates whether the employee is inside the office campus
     * purely using WiFi RSSI scan data + active connected network info.
     * 
     * @param wifiScanResults   Results from WifiManager.getScanResults()
     * @param connectedSsid     SSID of currently connected WiFi network (if any)
     * @param connectedBssid    BSSID of currently connected WiFi network (if any)
     * @param connectedRssi     RSSI dBm of currently connected WiFi network
     * @param connectedFreq     Frequency of currently connected WiFi network
     * @return GeoFenceResult   Full result
     */
    public GeoFenceResult checkIfInsideGeofence(List<ScanResult> wifiScanResults,
                                               String connectedSsid,
                                               String connectedBssid,
                                               int connectedRssi,
                                               int connectedFreq) {
        int visibleMeshAPCount     = 0;
        int strongAPCount          = 0;
        List<MeshAPInfo> detectedMeshAPs = new ArrayList<>();

        GeoFenceConfig.CampusNode strongestNode = null;
        int strongestSignal = -999;
        double nearestDistance = 999.0;
        String nearestBssid = null;
        String nearestSsid = null;

        // 1. Process passive scan results
        if (wifiScanResults != null && !wifiScanResults.isEmpty()) {
            for (ScanResult scanResult : wifiScanResults) {
                GeoFenceConfig.CampusNode matchedNode = GeoFenceConfig.findNodeByBssidOrSsid(
                        scanResult.BSSID, scanResult.SSID);

                boolean isMesh = (matchedNode != null) || isKnownMeshBSSID(scanResult.BSSID);

                if (isMesh && scanResult.level >= GeoFenceConfig.MIN_WIFI_SIGNAL_STRENGTH_DBM) {
                    visibleMeshAPCount++;
                    double estDist = estimateDistance(scanResult.level);

                    int nodeId  = matchedNode != null ? matchedNode.nodeId : 0;
                    String zone = matchedNode != null ? matchedNode.zoneName : "CAMPUS_AP";
                    String ssid = matchedNode != null ? matchedNode.ssid : (scanResult.SSID != null ? scanResult.SSID : "UNKNOWN");
                    String ip   = matchedNode != null ? matchedNode.ipAddress : "192.168.4.1";

                    detectedMeshAPs.add(new MeshAPInfo(
                            nodeId,
                            zone,
                            ssid,
                            ip,
                            scanResult.BSSID,
                            scanResult.level,
                            scanResult.frequency,
                            estDist
                    ));

                    if (scanResult.level > strongestSignal) {
                        strongestSignal = scanResult.level;
                        strongestNode   = matchedNode;
                        nearestDistance = estDist;
                        nearestBssid    = scanResult.BSSID;
                        nearestSsid     = ssid;
                    }

                    if (scanResult.level >= GeoFenceConfig.STRONG_SIGNAL_THRESHOLD_DBM) {
                        strongAPCount++;
                    }
                }
            }
        }

        // 2. Check if currently connected to a campus AP (critical fallback for scan throttling)
        boolean isConnectedToCampus = false;
        GeoFenceConfig.CampusNode connectedCampusNode = null;
        if (connectedBssid != null || connectedSsid != null) {
            connectedCampusNode = GeoFenceConfig.findNodeByBssidOrSsid(connectedBssid, connectedSsid);
            if (connectedCampusNode != null || isKnownMeshBSSID(connectedBssid)) {
                isConnectedToCampus = true;

                // Check if this node was already captured by the passive scan
                boolean alreadyInList = false;
                for (MeshAPInfo info : detectedMeshAPs) {
                    if ((connectedCampusNode != null && info.nodeId == connectedCampusNode.nodeId) ||
                        (connectedBssid != null && info.bssid.equalsIgnoreCase(connectedBssid))) {
                        alreadyInList = true;
                        break;
                    }
                }

                int effectiveRssi = (connectedRssi > -100 && connectedRssi < 0) ? connectedRssi : -60;
                double estDist = estimateDistance(effectiveRssi);

                if (!alreadyInList) {
                    visibleMeshAPCount++;
                    int nodeId = connectedCampusNode != null ? connectedCampusNode.nodeId : 0;
                    String zone = connectedCampusNode != null ? connectedCampusNode.zoneName : "CAMPUS_AP";
                    String ssid = connectedCampusNode != null ? connectedCampusNode.ssid : (connectedSsid != null ? connectedSsid : "CAMPUS_WIFI");
                    String ip = connectedCampusNode != null ? connectedCampusNode.ipAddress : "192.168.4.1";

                    detectedMeshAPs.add(new MeshAPInfo(
                            nodeId,
                            zone,
                            ssid,
                            ip,
                            connectedBssid != null ? connectedBssid : "--",
                            effectiveRssi,
                            connectedFreq,
                            estDist
                    ));
                }

                if (effectiveRssi > strongestSignal) {
                    strongestSignal = effectiveRssi;
                    strongestNode   = connectedCampusNode;
                    nearestDistance = estDist;
                    nearestBssid    = connectedBssid;
                    nearestSsid     = connectedCampusNode != null ? connectedCampusNode.ssid : connectedSsid;
                }

                if (effectiveRssi >= GeoFenceConfig.STRONG_SIGNAL_THRESHOLD_DBM) {
                    strongAPCount++;
                }
            }
        }

        // ─── Confidence Scoring (100% WiFi RSSI) ──────────────────
        float confidenceScore = 0.0f;
        if (visibleMeshAPCount >= GeoFenceConfig.MIN_VISIBLE_MESH_APS) {
            // Base confidence for detecting at least 1 campus node
            confidenceScore = 0.6f;

            // Bonus for seeing additional floor nodes (+0.15 each up to +0.2)
            int extraAPs = visibleMeshAPCount - GeoFenceConfig.MIN_VISIBLE_MESH_APS;
            confidenceScore += Math.min(extraAPs * 0.15f, 0.2f);

            // Bonus for strong signal APs (+0.10 each up to +0.2)
            confidenceScore += Math.min(strongAPCount * 0.10f, 0.2f);
        }

        // Being actively connected to an office node guarantees high confidence
        if (isConnectedToCampus) {
            confidenceScore = Math.max(confidenceScore, 0.85f);
        }
        confidenceScore = Math.min(confidenceScore, 1.0f);

        int avgSignalStrength = getAverageSignalStrength(detectedMeshAPs);

        // ─── Campus Presence Decision ─────────────────────────────
        boolean isInsideCampus = isConnectedToCampus ||
                ((visibleMeshAPCount >= GeoFenceConfig.MIN_VISIBLE_MESH_APS)
                && (confidenceScore >= GeoFenceConfig.CONFIDENCE_THRESHOLD));

        // ─── Zone / Floor Resolution ──────────────────────────────
        String currentZone;
        if (strongestNode != null) {
            currentZone = strongestNode.zoneName;
        } else if (connectedCampusNode != null) {
            currentZone = connectedCampusNode.zoneName;
        } else if (isInsideCampus) {
            currentZone = "CAMPUS (DETECTED)";
        } else {
            currentZone = "OUTSIDE CAMPUS";
        }

        return new GeoFenceResult(
                isInsideCampus,
                confidenceScore,
                visibleMeshAPCount,
                avgSignalStrength,
                detectedMeshAPs,
                currentZone,
                nearestSsid != null ? nearestSsid : "--",
                nearestBssid != null ? nearestBssid : "--",
                strongestSignal != -999 ? strongestSignal : -100,
                nearestDistance
        );
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPER METHODS
    // ═══════════════════════════════════════════════════════════════

    private boolean isKnownMeshBSSID(String bssid) {
        if (bssid == null) return false;
        return knownMeshBSSIDs.contains(bssid.toUpperCase());
    }

    private int getAverageSignalStrength(List<MeshAPInfo> meshAPs) {
        if (meshAPs == null || meshAPs.isEmpty()) return -100;

        int totalDbm = 0;
        for (MeshAPInfo ap : meshAPs) {
            totalDbm += ap.signalStrengthDbm;
        }
        return totalDbm / meshAPs.size();
    }

    public boolean isEmployeeInsideCampus(List<ScanResult> wifiScanResults) {
        return checkIfInsideGeofence(wifiScanResults).isInsideCampus;
    }
}
