namespace VeilGeoFenceService;

/// <summary>
/// Configuration class for office campus WiFi RSSI localization.
/// 
/// Uses purely WiFi signal scanning (RSSI) to determine whether an employee
/// is inside the campus and which floor/zone they are located on.
/// NO GPS is used.
/// </summary>
public static class GeoFenceConfig
{
    // ═══════════════════════════════════════════════════════════════
    //  CAMPUS WIFI NODES (INDOOR POSITIONING & SCAN-ONLY DETECTION)
    // ═══════════════════════════════════════════════════════════════

    public class CampusNode
    {
        public int    NodeId    { get; }
        public string ZoneName  { get; }
        public string SSID      { get; }
        public string IpAddress { get; }
        public string BSSID     { get; }

        public CampusNode(int nodeId, string zoneName, string ssid, string ipAddress, string bssid)
        {
            NodeId    = nodeId;
            ZoneName  = zoneName;
            SSID      = ssid;
            IpAddress = ipAddress;
            BSSID     = bssid.ToUpperInvariant();
        }
    }

    public static readonly CampusNode[] CAMPUS_NODES =
    {
        new(1, "RECEPTION", "OFFICE_RECEPTION", "192.168.4.1", "58:BF:25:92:F3:41"),
        new(2, "FLOOR_1",   "OFFICE_FLOOR1",    "192.168.4.1", "88:13:BF:09:D6:55"),
        new(3, "FLOOR_2",   "OFFICE_FLOOR2",    "192.168.4.1", "8C:94:DF:6D:73:81")
    };

    /// <summary>
    /// Known BSSIDs (MAC addresses) of every access point in the campus.
    /// </summary>
    public static readonly string[] OFFICE_MESH_BSSIDS =
    {
        "58:BF:25:92:F3:41",  // Node 1: RECEPTION
        "88:13:BF:09:D6:55",  // Node 2: FLOOR_1
        "8C:94:DF:6D:73:81"   // Node 3: FLOOR_2
    };

    public static readonly string[] OFFICE_MESH_SSIDS =
    {
        "OFFICE_RECEPTION",
        "OFFICE_FLOOR1",
        "OFFICE_FLOOR2"
    };

    public static CampusNode? FindNodeByBssidOrSsid(string? bssid, string? ssid)
    {
        if (!string.IsNullOrWhiteSpace(bssid))
        {
            string upper = bssid.ToUpperInvariant();
            foreach (var node in CAMPUS_NODES)
            {
                if (node.BSSID.Equals(upper, StringComparison.OrdinalIgnoreCase))
                    return node;
            }
        }
        if (!string.IsNullOrWhiteSpace(ssid))
        {
            string clean = ssid.Replace("\"", "").Trim();
            foreach (var node in CAMPUS_NODES)
            {
                if (node.SSID.Equals(clean, StringComparison.OrdinalIgnoreCase))
                    return node;
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════
    //  THRESHOLDS & WEIGHTS (PURE WIFI RSSI)
    // ═══════════════════════════════════════════════════════════════

    /// <summary>
    /// Minimum WiFi signal strength (in dBm) to consider a mesh AP as "reachable."
    /// </summary>
    public const int MIN_WIFI_SIGNAL_STRENGTH_DBM = -80;

    /// <summary>
    /// Minimum number of office mesh BSSIDs that must be visible for WiFi condition.
    /// </summary>
    public const int MIN_VISIBLE_MESH_APS = 1;

    /// <summary>
    /// WiFi signal strength (dBm) at which an AP is considered "strongly connected."
    /// </summary>
    public const int STRONG_SIGNAL_THRESHOLD_DBM = -55;

    /// <summary>
    /// Minimum confidence score (0.0 – 1.0) required to declare the employee inside.
    /// </summary>
    public const float CONFIDENCE_THRESHOLD = 0.5f;

    // ═══════════════════════════════════════════════════════════════
    //  TRACKING INTERVALS (in milliseconds)
    // ═══════════════════════════════════════════════════════════════
    public const int WIFI_SCAN_INTERVAL_MS       = 15_000;  // 15 seconds
    public const int GEOFENCE_CHECK_INTERVAL_MS  = 10_000;  // 10 seconds
}
