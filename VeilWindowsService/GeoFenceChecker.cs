namespace VeilGeoFenceService;

/// <summary>
/// Core conditional logic that determines whether an employee is INSIDE the
/// geo-fenced office campus, using a combination of GPS position and WiFi
/// mesh network signals.
/// 
/// DIRECT PORT of GeoFenceChecker.java from the Android project.
/// 
/// ══════════════════════════════════════════════════════════════════════
///  ALGORITHM OVERVIEW (SCAN-ONLY MODE)
/// ══════════════════════════════════════════════════════════════════════
/// 
///  The checker fuses two independent signals:
/// 
///  1. GPS SIGNAL  → Is the device's lat/lng within the geo-fence radius?
///                   Produces a score from 0.0 (far away) to 1.0 (at center).
/// 
///  2. WIFI SIGNAL → Are office mesh APs visible in a passive WiFi scan?
///                   (NO CONNECTION REQUIRED — scan-only approach)
///                   Produces a score based on:
///                     • Number of visible mesh APs
///                     • Signal strengths of those APs (proximity indicator)
/// 
///  Final decision:
///    confidence = (GPS_WEIGHT × gpsScore) + (WIFI_WEIGHT × wifiScore)
///    isInside   = confidence >= CONFIDENCE_THRESHOLD
/// 
///  WiFi is weighted MORE than GPS (0.6 vs 0.4) because:
///    • GPS is unreliable indoors (signal bounce, multipath)
///    • Mesh WiFi APs are physically inside the campus — seeing them is
///      strong proof of physical presence
///    • Mesh topology means multiple APs are visible simultaneously,
///      providing triangulation-like certainty
///    • Signal strengths indicate physical proximity without needing
///      to connect to the network
/// 
/// ══════════════════════════════════════════════════════════════════════
/// </summary>
public class GeoFenceChecker
{
    // ─── Result Object ────────────────────────────────────────────

    /// <summary>
    /// Encapsulates the full result of a geo-fence check.
    /// </summary>
    public class GeoFenceResult
    {
        public bool   IsInsideCampus       { get; }
        public float  ConfidenceScore      { get; }  // 0.0 – 1.0
        public float  GpsScore             { get; }  // 0.0 – 1.0
        public float  WifiScore            { get; }  // 0.0 – 1.0
        public bool   IsGpsInsideRadius    { get; }
        public int    VisibleMeshAPCount   { get; }
        public float  DistanceFromCenter   { get; }  // meters
        public int    AverageSignalStrength { get; } // avg dBm of detected mesh APs
        public List<MeshAPInfo> DetectedMeshAPs { get; }

        public GeoFenceResult(
            bool isInsideCampus, float confidenceScore,
            float gpsScore, float wifiScore,
            bool isGpsInsideRadius,
            int visibleMeshAPCount, float distanceFromCenter,
            int averageSignalStrength, List<MeshAPInfo> detectedMeshAPs)
        {
            IsInsideCampus        = isInsideCampus;
            ConfidenceScore       = confidenceScore;
            GpsScore              = gpsScore;
            WifiScore             = wifiScore;
            IsGpsInsideRadius     = isGpsInsideRadius;
            VisibleMeshAPCount    = visibleMeshAPCount;
            DistanceFromCenter    = distanceFromCenter;
            AverageSignalStrength = averageSignalStrength;
            DetectedMeshAPs       = detectedMeshAPs;
        }

        public override string ToString()
        {
            return $"GeoFenceResult{{" +
                   $"IsInsideCampus={IsInsideCampus}" +
                   $", Confidence={ConfidenceScore:F2}" +
                   $", GpsScore={GpsScore:F2}" +
                   $", WifiScore={WifiScore:F2}" +
                   $", Distance={DistanceFromCenter:F1}m" +
                   $", MeshAPs={VisibleMeshAPCount}" +
                   $", AvgSignal={AverageSignalStrength}dBm" +
                   $"}}";
        }
    }

    /// <summary>
    /// Info about a single detected mesh AP.
    /// </summary>
    public class MeshAPInfo
    {
        public string BSSID              { get; }
        public int    SignalStrengthDbm  { get; }
        public int    Frequency          { get; }

        public MeshAPInfo(string bssid, int signalStrengthDbm, int frequency)
        {
            BSSID              = bssid;
            SignalStrengthDbm  = signalStrengthDbm;
            Frequency          = frequency;
        }
    }

    // ─── Known Mesh BSSIDs (preloaded into a HashSet for O(1) lookup) ──
    private readonly HashSet<string> _knownMeshBSSIDs;

    public GeoFenceChecker()
    {
        _knownMeshBSSIDs = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (string bssid in GeoFenceConfig.OFFICE_MESH_BSSIDS)
        {
            _knownMeshBSSIDs.Add(bssid.ToUpperInvariant());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  MAIN CONDITIONAL CHECK
    // ═══════════════════════════════════════════════════════════════

    /// <summary>
    /// ★ PRIMARY METHOD ★ (SCAN-ONLY MODE)
    /// 
    /// Evaluates whether the employee is inside the geo-fenced office campus
    /// by combining GPS location and WiFi mesh scan data.
    /// 
    /// NO WiFi connection is required — detection is purely based on
    /// scanning visible mesh AP signal strengths.
    /// </summary>
    /// <param name="currentLatitude">Device's current latitude (null if GPS unavailable)</param>
    /// <param name="currentLongitude">Device's current longitude (null if GPS unavailable)</param>
    /// <param name="gpsAccuracy">GPS accuracy in meters (null if unavailable)</param>
    /// <param name="wifiScanResults">Results from WiFi scan (may be null or empty)</param>
    /// <returns>Full result including confidence scores and details</returns>
    public GeoFenceResult CheckIfInsideGeofence(
        double? currentLatitude,
        double? currentLongitude,
        float? gpsAccuracy,
        List<WifiScanResult>? wifiScanResults)
    {
        // ─── Step 1: Evaluate GPS condition ───────────────────────
        float gpsScore           = 0.0f;
        bool  isGpsInsideRadius  = false;
        float distanceFromCenter = float.MaxValue;

        if (currentLatitude.HasValue && currentLongitude.HasValue)
        {
            distanceFromCenter = CalculateDistance(
                currentLatitude.Value,
                currentLongitude.Value,
                GeoFenceConfig.OFFICE_LATITUDE,
                GeoFenceConfig.OFFICE_LONGITUDE
            );

            if (distanceFromCenter <= GeoFenceConfig.GEO_FENCE_RADIUS_METERS)
            {
                // Inside the geo-fence radius — score inversely proportional to distance
                // At center (0m) → 1.0, at boundary (radius) → 0.3
                isGpsInsideRadius = true;
                gpsScore = 1.0f - (0.7f * (distanceFromCenter / GeoFenceConfig.GEO_FENCE_RADIUS_METERS));
            }
            else
            {
                // Outside the radius — rapidly decaying score
                // Up to 1.5× radius → small non-zero score (GPS might be inaccurate)
                float overshootRatio = distanceFromCenter / GeoFenceConfig.GEO_FENCE_RADIUS_METERS;
                if (overshootRatio <= 1.5f)
                {
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
        int   visibleMeshAPCount   = 0;
        int   strongAPCount        = 0;
        var   detectedMeshAPs      = new List<MeshAPInfo>();

        if (wifiScanResults != null && wifiScanResults.Count > 0)
        {
            foreach (var scanResult in wifiScanResults)
            {
                if (IsKnownMeshBSSID(scanResult.BSSID)
                    && scanResult.SignalStrengthDbm >= GeoFenceConfig.MIN_WIFI_SIGNAL_STRENGTH_DBM)
                {
                    visibleMeshAPCount++;
                    detectedMeshAPs.Add(new MeshAPInfo(
                        scanResult.BSSID,
                        scanResult.SignalStrengthDbm,
                        scanResult.Frequency
                    ));

                    if (scanResult.SignalStrengthDbm >= GeoFenceConfig.STRONG_SIGNAL_THRESHOLD_DBM)
                    {
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
            if (visibleMeshAPCount >= GeoFenceConfig.MIN_VISIBLE_MESH_APS)
            {
                // Base: 0.5 for meeting the minimum AP threshold
                float apScore = 0.5f;

                // Bonus: up to 0.25 for additional APs beyond the minimum
                int extraAPs = visibleMeshAPCount - GeoFenceConfig.MIN_VISIBLE_MESH_APS;
                apScore += Math.Min(extraAPs * 0.05f, 0.25f);

                // Bonus: up to 0.25 for strong signal APs (close proximity)
                apScore += Math.Min(strongAPCount * 0.05f, 0.25f);

                wifiScore = apScore;
            }
            else if (visibleMeshAPCount == 1)
            {
                // Only 1 AP visible — might be near the boundary
                wifiScore = 0.2f;
            }
        }

        // Cap WiFi score at 1.0
        wifiScore = Math.Min(wifiScore, 1.0f);

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

        int avgSignalStrength = GetAverageSignalStrength(detectedMeshAPs);
        bool isInsideCampus;

        if (confidenceScore >= GeoFenceConfig.CONFIDENCE_THRESHOLD)
        {
            // CONDITION 1: Weighted confidence score exceeds threshold
            isInsideCampus = true;
        }
        else if (isGpsInsideRadius
                 && visibleMeshAPCount >= GeoFenceConfig.MIN_VISIBLE_MESH_APS)
        {
            // CONDITION 2: GPS confirms radius AND WiFi scan confirms mesh visibility
            // Even if individual scores are low, dual confirmation is reliable
            isInsideCampus = true;
        }
        else if (visibleMeshAPCount >= 3
                 && avgSignalStrength >= GeoFenceConfig.STRONG_SIGNAL_THRESHOLD_DBM)
        {
            // CONDITION 3: Deep indoor override — GPS unavailable but WiFi
            // evidence is overwhelming (multiple strong mesh APs visible)
            isInsideCampus = true;
        }
        else
        {
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

    /// <summary>
    /// Checks if a BSSID belongs to the known office mesh network.
    /// Case-insensitive comparison.
    /// </summary>
    private bool IsKnownMeshBSSID(string? bssid)
    {
        if (bssid == null) return false;
        return _knownMeshBSSIDs.Contains(bssid.ToUpperInvariant());
    }

    /// <summary>
    /// Calculates the distance in meters between two GPS coordinates
    /// using the Haversine formula.
    /// </summary>
    public static float CalculateDistance(double lat1, double lon1, double lat2, double lon2)
    {
        const double R = 6371000; // Earth's radius in meters

        double dLat = DegreesToRadians(lat2 - lat1);
        double dLon = DegreesToRadians(lon2 - lon1);

        double a = Math.Sin(dLat / 2) * Math.Sin(dLat / 2)
                 + Math.Cos(DegreesToRadians(lat1)) * Math.Cos(DegreesToRadians(lat2))
                 * Math.Sin(dLon / 2) * Math.Sin(dLon / 2);

        double c = 2 * Math.Atan2(Math.Sqrt(a), Math.Sqrt(1 - a));

        return (float)(R * c);
    }

    private static double DegreesToRadians(double degrees)
    {
        return degrees * Math.PI / 180.0;
    }

    /// <summary>
    /// Computes the average signal strength of detected mesh APs.
    /// </summary>
    private static int GetAverageSignalStrength(List<MeshAPInfo> meshAPs)
    {
        if (meshAPs == null || meshAPs.Count == 0) return -100;

        int totalDbm = 0;
        foreach (var ap in meshAPs)
        {
            totalDbm += ap.SignalStrengthDbm;
        }
        return totalDbm / meshAPs.Count;
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONVENIENCE: Simple boolean check
    // ═══════════════════════════════════════════════════════════════

    /// <summary>
    /// Simplified version — returns just true/false.
    /// </summary>
    public bool IsEmployeeInsideCampus(
        double? currentLatitude,
        double? currentLongitude,
        float? gpsAccuracy,
        List<WifiScanResult>? wifiScanResults)
    {
        return CheckIfInsideGeofence(
            currentLatitude, currentLongitude,
            gpsAccuracy, wifiScanResults
        ).IsInsideCampus;
    }
}

/// <summary>
/// Represents a single WiFi scan result — the Windows equivalent of Android's ScanResult.
/// </summary>
public class WifiScanResult
{
    public string BSSID              { get; set; } = string.Empty;
    public string SSID               { get; set; } = string.Empty;
    public int    SignalStrengthDbm   { get; set; }
    public int    Frequency          { get; set; }

    public override string ToString()
    {
        return $"WiFi[SSID={SSID}, BSSID={BSSID}, Signal={SignalStrengthDbm}dBm, Freq={Frequency}MHz]";
    }
}
