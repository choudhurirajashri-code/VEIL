namespace VeilGeoFenceService;

/// <summary>
/// Configuration class for the office campus geo-fence.
/// 
/// DIRECT PORT of GeoFenceConfig.java from the Android project.
/// 
/// Defines the geo-fence boundary (center + radius), known mesh WiFi access points,
/// and thresholds used to determine if an employee is inside the office campus.
/// 
/// MESH TOPOLOGY AWARENESS:
/// ─────────────────────────
/// In a mesh WiFi network, multiple access points (APs) share the same SSID but have
/// unique BSSIDs (MAC addresses). The employee's device will roam between APs seamlessly.
/// We track ALL known BSSIDs in the mesh so that connecting to ANY mesh node counts as
/// being "on the office network."
/// </summary>
public static class GeoFenceConfig
{
    // ═══════════════════════════════════════════════════════════════
    //  GEO-FENCE CENTER (Office Campus)
    //  Replace these with your actual office campus coordinates.
    // ═══════════════════════════════════════════════════════════════
    public const double OFFICE_LATITUDE  = 28.6139;   // Example: New Delhi
    public const double OFFICE_LONGITUDE = 77.2090;

    /// <summary>
    /// Geo-fence radius in meters — defines the circular boundary around the office.
    /// </summary>
    public const float GEO_FENCE_RADIUS_METERS = 200.0f;

    // ═══════════════════════════════════════════════════════════════
    //  MESH WIFI CONFIGURATION (SCAN-ONLY)
    //  All access points in the office mesh network.
    //  We scan for these BSSIDs — NO connection to the mesh is required.
    //  BSSIDs are unique per AP; SSID is irrelevant in scan-only mode.
    // ═══════════════════════════════════════════════════════════════

    /// <summary>
    /// Known BSSIDs (MAC addresses) of every access point in the mesh.
    /// Add/remove entries as APs are added/removed from the mesh.
    /// 
    /// Mesh topology layout example:
    /// 
    ///   [AP-1: Lobby] ──── [AP-2: Floor 1] ──── [AP-3: Floor 2]
    ///        │                    │                     │
    ///   [AP-4: Cafeteria]   [AP-5: Conf Room]   [AP-6: Server Room]
    ///        │                    │
    ///   [AP-7: Parking]    [AP-8: Terrace]
    /// </summary>
    public static readonly string[] OFFICE_MESH_BSSIDS =
    {
        "AA:BB:CC:DD:EE:01",  // AP-1: Lobby
        "AA:BB:CC:DD:EE:02",  // AP-2: Floor 1
        "AA:BB:CC:DD:EE:03",  // AP-3: Floor 2
        "AA:BB:CC:DD:EE:04",  // AP-4: Cafeteria
        "AA:BB:CC:DD:EE:05",  // AP-5: Conference Room
        "AA:BB:CC:DD:EE:06",  // AP-6: Server Room
        "AA:BB:CC:DD:EE:07",  // AP-7: Parking Area
        "AA:BB:CC:DD:EE:08",  // AP-8: Terrace
    };

    // ═══════════════════════════════════════════════════════════════
    //  THRESHOLDS & WEIGHTS
    // ═══════════════════════════════════════════════════════════════

    /// <summary>
    /// Minimum WiFi signal strength (in dBm) to consider a mesh AP as "reachable."
    /// Typical values:
    ///   -30 dBm = Excellent (very close to AP)
    ///   -50 dBm = Good
    ///   -70 dBm = Fair
    ///   -80 dBm = Weak
    ///   -90 dBm = Unusable
    /// </summary>
    public const int MIN_WIFI_SIGNAL_STRENGTH_DBM = -75;

    /// <summary>
    /// Minimum number of office mesh BSSIDs that must be visible (in scan results)
    /// for the WiFi condition to be considered MET.
    /// 
    /// In a mesh topology, seeing multiple APs simultaneously is strong evidence
    /// that the device is physically inside the campus (not just near the boundary).
    /// </summary>
    public const int MIN_VISIBLE_MESH_APS = 2;

    /// <summary>
    /// WiFi signal strength (dBm) at which an AP is considered "strongly connected."
    /// Used for the confidence scoring system.
    /// </summary>
    public const int STRONG_SIGNAL_THRESHOLD_DBM = -55;

    // ═══════════════════════════════════════════════════════════════
    //  CONFIDENCE SCORING WEIGHTS
    //  The final "inside campus" decision uses a weighted score:
    //    Score = (GPS_WEIGHT × gpsScore) + (WIFI_WEIGHT × wifiScore)
    //  Employee is INSIDE if score >= CONFIDENCE_THRESHOLD
    // ═══════════════════════════════════════════════════════════════
    public const float GPS_WEIGHT  = 0.4f;
    public const float WIFI_WEIGHT = 0.6f;   // WiFi weighted higher — more reliable indoors

    /// <summary>
    /// Minimum combined confidence score (0.0 – 1.0) required to declare
    /// the employee as "inside the geo-fenced campus."
    /// </summary>
    public const float CONFIDENCE_THRESHOLD = 0.6f;

    // ═══════════════════════════════════════════════════════════════
    //  TRACKING INTERVALS (in milliseconds)
    // ═══════════════════════════════════════════════════════════════
    public const int GPS_UPDATE_INTERVAL_MS      = 10_000;  // 10 seconds
    public const int GPS_FASTEST_INTERVAL_MS     = 5_000;   // 5 seconds
    public const int WIFI_SCAN_INTERVAL_MS       = 15_000;  // 15 seconds
    public const int GEOFENCE_CHECK_INTERVAL_MS  = 10_000;  // 10 seconds
}
