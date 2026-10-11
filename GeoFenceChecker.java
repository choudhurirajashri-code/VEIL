package com.example.officegeotracker;

import android.location.Location;
import android.net.wifi.ScanResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Core conditional logic that determines whether an employee is INSIDE the
 * geo-fenced office campus, using a combination of GPS position and WiFi
 * mesh network signals.
 * 
 * ══════════════════════════════════════════════════════════════════════
 *  ALGORITHM OVERVIEW
 * ══════════════════════════════════════════════════════════════════════
 * 
 *  The checker fuses two independent signals:
 * 
 *  1. GPS SIGNAL  → Is the device's lat/lng within the geo-fence radius?
 *                   Produces a score from 0.0 (far away) to 1.0 (at center).
 * 
 *  2. WIFI SIGNAL → Is the device seeing/connected to the office mesh APs?
 *                   Produces a score based on:
 *                     • Number of visible mesh APs
 *                     • Signal strengths of those APs
 *                     • Whether the device is actively connected to the mesh SSID
 * 
 *  Final decision:
 *    confidence = (GPS_WEIGHT × gpsScore) + (WIFI_WEIGHT × wifiScore)
 *    isInside   = confidence >= CONFIDENCE_THRESHOLD
 * 
 *  WiFi is weighted MORE than GPS (0.6 vs 0.4) because:
 *    • GPS is unreliable indoors (signal bounce, multipath)
 *    • Mesh WiFi APs are physically inside the campus — seeing them is
 *      strong proof of physical presence
 *    • Mesh topology means multiple APs are visible simultaneously,
 *      providing triangulation-like certainty
 * 
 * ══════════════════════════════════════════════════════════════════════
 */
public class GeoFenceChecker {

    // ─── Result Object ────────────────────────────────────────────
    /**
     * Encapsulates the full result of a geo-fence check.
     */
    public static class GeoFenceResult {
        public final boolean isInsideCampus;
        public final float   confidenceScore;     // 0.0 – 1.0
        public final float   gpsScore;            // 0.0 – 1.0
        public final float   wifiScore;           // 0.0 – 1.0
        public final boolean isGpsInsideRadius;
        public final boolean isConnectedToMesh;
        public final int     visibleMeshAPCount;
        public final float   distanceFromCenter;  // meters
        public final String  connectedBSSID;      // currently connected mesh AP (or null)
        public final List<MeshAPInfo> detectedMeshAPs;

        public GeoFenceResult(boolean isInsideCampus, float confidenceScore,
                              float gpsScore, float wifiScore,
                              boolean isGpsInsideRadius, boolean isConnectedToMesh,
                              int visibleMeshAPCount, float distanceFromCenter,
                              String connectedBSSID, List<MeshAPInfo> detectedMeshAPs) {
            this.isInsideCampus     = isInsideCampus;
            this.confidenceScore    = confidenceScore;
            this.gpsScore           = gpsScore;
            this.wifiScore          = wifiScore;
            this.isGpsInsideRadius  = isGpsInsideRadius;
            this.isConnectedToMesh  = isConnectedToMesh;
            this.visibleMeshAPCount = visibleMeshAPCount;
            this.distanceFromCenter = distanceFromCenter;
            this.connectedBSSID    = connectedBSSID;
            this.detectedMeshAPs   = detectedMeshAPs;
        }

        @Override
        public String toString() {
            return "GeoFenceResult{" +
                    "isInsideCampus=" + isInsideCampus +
                    ", confidence=" + String.format("%.2f", confidenceScore) +
                    ", gpsScore=" + String.format("%.2f", gpsScore) +
                    ", wifiScore=" + String.format("%.2f", wifiScore) +
                    ", distance=" + String.format("%.1fm", distanceFromCenter) +
                    ", meshAPs=" + visibleMeshAPCount +
                    ", connected=" + isConnectedToMesh +
                    '}';
        }
    }

    /**
     * Info about a single detected mesh AP.
     */
    public static class MeshAPInfo {
        public final String bssid;
        public final int    signalStrengthDbm;
        public final int    frequency;

        public MeshAPInfo(String bssid, int signalStrengthDbm, int frequency) {
            this.bssid              = bssid;
            this.signalStrengthDbm = signalStrengthDbm;
            this.frequency         = frequency;
        }
    }

    // ─── Known Mesh BSSIDs (preloaded into a Set for O(1) lookup) ──
    private final Set<String> knownMeshBSSIDs;

    public GeoFenceChecker() {
        knownMeshBSSIDs = new HashSet<>();
        for (String bssid : GeoFenceConfig.OFFICE_MESH_BSSIDS) {
            knownMeshBSSIDs.add(bssid.toUpperCase());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  MAIN CONDITIONAL CHECK
    // ═══════════════════════════════════════════════════════════════

    /**
     * ★ PRIMARY METHOD ★
     * 
     * Evaluates whether the employee is inside the geo-fenced office campus
     * by combining GPS location and WiFi mesh scan data.
     * 
     * @param currentLocation   The device's current GPS location (may be null if unavailable)
     * @param wifiScanResults   Results from WifiManager.getScanResults() (may be null or empty)
     * @param connectedSSID     The SSID the device is currently connected to (may be null)
     * @param connectedBSSID    The BSSID the device is currently connected to (may be null)
     * @return GeoFenceResult   Full result including confidence scores and details
     */
    public GeoFenceResult checkIfInsideGeofence(
            Location currentLocation,
            List<ScanResult> wifiScanResults,
            String connectedSSID,
            String connectedBSSID) {

        // ─── Step 1: Evaluate GPS condition ───────────────────────
        float gpsScore           = 0.0f;
        boolean isGpsInsideRadius = false;
        float distanceFromCenter = Float.MAX_VALUE;

        if (currentLocation != null) {
            distanceFromCenter = calculateDistance(
                    currentLocation.getLatitude(),
                    currentLocation.getLongitude(),
                    GeoFenceConfig.OFFICE_LATITUDE,
                    GeoFenceConfig.OFFICE_LONGITUDE
            );

            if (distanceFromCenter <= GeoFenceConfig.GEO_FENCE_RADIUS_METERS) {
                // Inside the geo-fence radius — score inversely proportional to distance
                // At center (0m) → 1.0, at boundary (radius) → 0.3
                isGpsInsideRadius = true;
                gpsScore = 1.0f - (0.7f * (distanceFromCenter / GeoFenceConfig.GEO_FENCE_RADIUS_METERS));
            } else {
                // Outside the radius — rapidly decaying score
                // Up to 1.5× radius → small non-zero score (GPS might be inaccurate)
                float overshootRatio = distanceFromCenter / GeoFenceConfig.GEO_FENCE_RADIUS_METERS;
                if (overshootRatio <= 1.5f) {
                    gpsScore = 0.2f * (1.0f - ((overshootRatio - 1.0f) / 0.5f));
                }
                // Beyond 1.5× radius → gpsScore stays 0.0
            }
        }
        // If GPS location is null, gpsScore remains 0.0 — WiFi alone must carry the decision.

        // ─── Step 2: Evaluate WiFi mesh condition ─────────────────
        float wifiScore            = 0.0f;
        boolean isConnectedToMesh  = false;
        int visibleMeshAPCount     = 0;
        List<MeshAPInfo> detectedMeshAPs = new ArrayList<>();

        // Check 2a: Is the device ACTIVELY CONNECTED to the office mesh?
        if (connectedSSID != null && connectedBSSID != null) {
            if (connectedSSID.equals(GeoFenceConfig.OFFICE_MESH_SSID)
                    && isKnownMeshBSSID(connectedBSSID)) {
                isConnectedToMesh = true;
                // Being connected to the mesh is very strong evidence → base score of 0.5
                wifiScore += 0.5f;
            }
        }

        // Check 2b: How many mesh APs are visible in the WiFi scan?
        if (wifiScanResults != null && !wifiScanResults.isEmpty()) {
            int strongAPCount = 0;

            for (ScanResult scanResult : wifiScanResults) {
                if (isKnownMeshBSSID(scanResult.BSSID)
                        && scanResult.level >= GeoFenceConfig.MIN_WIFI_SIGNAL_STRENGTH_DBM) {

                    visibleMeshAPCount++;
                    detectedMeshAPs.add(new MeshAPInfo(
                            scanResult.BSSID,
                            scanResult.level,
                            scanResult.frequency
                    ));

                    if (scanResult.level >= GeoFenceConfig.STRONG_SIGNAL_THRESHOLD_DBM) {
                        strongAPCount++;
                    }
                }
            }

            // Scoring based on visible mesh AP count:
            //   • Each visible AP adds to the score (diminishing returns)
            //   • Seeing ≥ MIN_VISIBLE_MESH_APS is the minimum bar
            //   • Strong signals get bonus points
            if (visibleMeshAPCount >= GeoFenceConfig.MIN_VISIBLE_MESH_APS) {
                // Base: 0.3 for meeting the minimum AP threshold
                float apScore = 0.3f;

                // Bonus: up to 0.15 for additional APs beyond the minimum
                int extraAPs = visibleMeshAPCount - GeoFenceConfig.MIN_VISIBLE_MESH_APS;
                apScore += Math.min(extraAPs * 0.05f, 0.15f);

                // Bonus: up to 0.05 for strong signals
                apScore += Math.min(strongAPCount * 0.025f, 0.05f);

                wifiScore += apScore;
            } else if (visibleMeshAPCount == 1) {
                // Only 1 AP visible — might be near the boundary
                wifiScore += 0.1f;
            }
        }

        // Cap WiFi score at 1.0
        wifiScore = Math.min(wifiScore, 1.0f);

        // ─── Step 3: Combined confidence score ────────────────────
        float confidenceScore = (GeoFenceConfig.GPS_WEIGHT * gpsScore)
                              + (GeoFenceConfig.WIFI_WEIGHT * wifiScore);

        // ═══════════════════════════════════════════════════════════
        //  ★ THE CONDITIONAL STATEMENT ★
        //  
        //  Employee is considered INSIDE the geo-fenced campus if:
        //    CONDITION 1: Combined confidence score meets threshold
        //                 OR
        //    CONDITION 2: GPS is inside radius AND connected to mesh WiFi
        //                 (override — both signals agree, skip confidence calc)
        //                 OR
        //    CONDITION 3: Connected to mesh WiFi AND sees ≥3 mesh APs with
        //                 strong signal (WiFi-only override for deep indoor areas
        //                 where GPS is completely unavailable)
        // ═══════════════════════════════════════════════════════════

        boolean isInsideCampus;

        if (confidenceScore >= GeoFenceConfig.CONFIDENCE_THRESHOLD) {
            // CONDITION 1: Weighted confidence score exceeds threshold
            isInsideCampus = true;

        } else if (isGpsInsideRadius && isConnectedToMesh) {
            // CONDITION 2: Both GPS and WiFi independently confirm presence
            // Even if individual scores are low, dual confirmation is reliable
            isInsideCampus = true;

        } else if (isConnectedToMesh
                && visibleMeshAPCount >= 3
                && getAverageSignalStrength(detectedMeshAPs)
                        >= GeoFenceConfig.STRONG_SIGNAL_THRESHOLD_DBM) {
            // CONDITION 3: Deep indoor override — GPS unavailable but WiFi
            // evidence is overwhelming (connected + multiple strong APs)
            isInsideCampus = true;

        } else {
            // None of the conditions met — employee is OUTSIDE the campus
            isInsideCampus = false;
        }

        return new GeoFenceResult(
                isInsideCampus, confidenceScore,
                gpsScore, wifiScore,
                isGpsInsideRadius, isConnectedToMesh,
                visibleMeshAPCount, distanceFromCenter,
                connectedBSSID, detectedMeshAPs
        );
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPER METHODS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Checks if a BSSID belongs to the known office mesh network.
     * Case-insensitive comparison.
     */
    private boolean isKnownMeshBSSID(String bssid) {
        if (bssid == null) return false;
        return knownMeshBSSIDs.contains(bssid.toUpperCase());
    }

    /**
     * Calculates the distance in meters between two GPS coordinates
     * using the Haversine formula.
     */
    private float calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000; // Earth's radius in meters

        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return (float) (R * c);
    }

    /**
     * Computes the average signal strength of detected mesh APs.
     */
    private int getAverageSignalStrength(List<MeshAPInfo> meshAPs) {
        if (meshAPs == null || meshAPs.isEmpty()) return -100;

        int totalDbm = 0;
        for (MeshAPInfo ap : meshAPs) {
            totalDbm += ap.signalStrengthDbm;
        }
        return totalDbm / meshAPs.size();
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONVENIENCE: Simple boolean check
    // ═══════════════════════════════════════════════════════════════

    /**
     * Simplified version — returns just true/false.
     */
    public boolean isEmployeeInsideCampus(
            Location currentLocation,
            List<ScanResult> wifiScanResults,
            String connectedSSID,
            String connectedBSSID) {

        return checkIfInsideGeofence(
                currentLocation, wifiScanResults,
                connectedSSID, connectedBSSID
        ).isInsideCampus;
    }
}
