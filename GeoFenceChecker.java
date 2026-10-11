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
 *  ALGORITHM OVERVIEW (SCAN-ONLY MODE)
 * ══════════════════════════════════════════════════════════════════════
 * 
 *  The checker fuses two independent signals:
 * 
 *  1. GPS SIGNAL  → Is the device's lat/lng within the geo-fence radius?
 *                   Produces a score from 0.0 (far away) to 1.0 (at center).
 * 
 *  2. WIFI SIGNAL → Are office mesh APs visible in a passive WiFi scan?
 *                   (NO CONNECTION REQUIRED — scan-only approach)
 *                   Produces a score based on:
 *                     • Number of visible mesh APs
 *                     • Signal strengths of those APs (proximity indicator)
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
 *    • Signal strengths indicate physical proximity without needing
 *      to connect to the network
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
        public final int     visibleMeshAPCount;
        public final float   distanceFromCenter;  // meters
        public final int     averageSignalStrength; // avg dBm of detected mesh APs
        public final List<MeshAPInfo> detectedMeshAPs;

        public GeoFenceResult(boolean isInsideCampus, float confidenceScore,
                              float gpsScore, float wifiScore,
                              boolean isGpsInsideRadius,
                              int visibleMeshAPCount, float distanceFromCenter,
                              int averageSignalStrength, List<MeshAPInfo> detectedMeshAPs) {
            this.isInsideCampus       = isInsideCampus;
            this.confidenceScore      = confidenceScore;
            this.gpsScore             = gpsScore;
            this.wifiScore            = wifiScore;
            this.isGpsInsideRadius    = isGpsInsideRadius;
            this.visibleMeshAPCount   = visibleMeshAPCount;
            this.distanceFromCenter   = distanceFromCenter;
            this.averageSignalStrength = averageSignalStrength;
            this.detectedMeshAPs      = detectedMeshAPs;
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
                    ", avgSignal=" + averageSignalStrength + "dBm" +
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
     * ★ PRIMARY METHOD ★ (SCAN-ONLY MODE)
     * 
     * Evaluates whether the employee is inside the geo-fenced office campus
     * by combining GPS location and WiFi mesh scan data.
     * 
     * NO WiFi connection is required — detection is purely based on
     * scanning visible mesh AP signal strengths.
     * 
     * @param currentLocation   The device's current GPS location (may be null if unavailable)
     * @param wifiScanResults   Results from WifiManager.getScanResults() (may be null or empty)
     * @return GeoFenceResult   Full result including confidence scores and details
     */
    public GeoFenceResult checkIfInsideGeofence(
            Location currentLocation,
            List<ScanResult> wifiScanResults) {

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

        // ─── Step 2: Evaluate WiFi mesh condition (SCAN-ONLY) ─────
        //
        // No WiFi connection required. We passively scan for visible
        // mesh APs and use their signal strengths to determine proximity.
        //
        float wifiScore            = 0.0f;
        int visibleMeshAPCount     = 0;
        int strongAPCount          = 0;
        List<MeshAPInfo> detectedMeshAPs = new ArrayList<>();

        if (wifiScanResults != null && !wifiScanResults.isEmpty()) {
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

            // ── WiFi Scoring (scan-only — no connection bonus) ──────
            //
            // Since we don't require connecting to the mesh, the scan-based
            // scoring carries the full WiFi weight:
            //
            //   • Seeing ≥ MIN_VISIBLE_MESH_APS → base 0.5
            //   • Extra APs beyond minimum → up to +0.25 (proximity)
            //   • Strong signal APs → up to +0.25 (close proximity)
            //   • Only 1 AP visible → 0.2 (boundary area)
            //
            if (visibleMeshAPCount >= GeoFenceConfig.MIN_VISIBLE_MESH_APS) {
                // Base: 0.5 for meeting the minimum AP threshold
                float apScore = 0.5f;

                // Bonus: up to 0.25 for additional APs beyond the minimum
                int extraAPs = visibleMeshAPCount - GeoFenceConfig.MIN_VISIBLE_MESH_APS;
                apScore += Math.min(extraAPs * 0.05f, 0.25f);

                // Bonus: up to 0.25 for strong signal APs (close proximity)
                apScore += Math.min(strongAPCount * 0.05f, 0.25f);

                wifiScore = apScore;
            } else if (visibleMeshAPCount == 1) {
                // Only 1 AP visible — might be near the boundary
                wifiScore = 0.2f;
            }
        }

        // Cap WiFi score at 1.0
        wifiScore = Math.min(wifiScore, 1.0f);

        // ─── Step 3: Combined confidence score ────────────────────
        float confidenceScore = (GeoFenceConfig.GPS_WEIGHT * gpsScore)
                              + (GeoFenceConfig.WIFI_WEIGHT * wifiScore);

        // ═══════════════════════════════════════════════════════════
        //  ★ THE CONDITIONAL STATEMENT ★ (SCAN-ONLY MODE)
        //  
        //  Employee is considered INSIDE the geo-fenced campus if:
        //    CONDITION 1: Combined confidence score meets threshold
        //                 OR
        //    CONDITION 2: GPS is inside radius AND sees ≥ MIN_VISIBLE_MESH_APS
        //                 (override — both signals agree independently)
        //                 OR
        //    CONDITION 3: Sees ≥3 mesh APs with strong average signal
        //                 (WiFi-only override for deep indoor areas
        //                 where GPS is completely unavailable)
        // ═══════════════════════════════════════════════════════════

        int avgSignalStrength = getAverageSignalStrength(detectedMeshAPs);
        boolean isInsideCampus;

        if (confidenceScore >= GeoFenceConfig.CONFIDENCE_THRESHOLD) {
            // CONDITION 1: Weighted confidence score exceeds threshold
            isInsideCampus = true;

        } else if (isGpsInsideRadius
                && visibleMeshAPCount >= GeoFenceConfig.MIN_VISIBLE_MESH_APS) {
            // CONDITION 2: GPS confirms radius AND WiFi scan confirms mesh visibility
            // Even if individual scores are low, dual confirmation is reliable
            isInsideCampus = true;

        } else if (visibleMeshAPCount >= 3
                && avgSignalStrength >= GeoFenceConfig.STRONG_SIGNAL_THRESHOLD_DBM) {
            // CONDITION 3: Deep indoor override — GPS unavailable but WiFi
            // evidence is overwhelming (multiple strong mesh APs visible)
            isInsideCampus = true;

        } else {
            // None of the conditions met — employee is OUTSIDE the campus
            isInsideCampus = false;
        }

        return new GeoFenceResult(
                isInsideCampus, confidenceScore,
                gpsScore, wifiScore,
                isGpsInsideRadius,
                visibleMeshAPCount, distanceFromCenter,
                avgSignalStrength, detectedMeshAPs
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
            List<ScanResult> wifiScanResults) {

        return checkIfInsideGeofence(
                currentLocation, wifiScanResults
        ).isInsideCampus;
    }
}
