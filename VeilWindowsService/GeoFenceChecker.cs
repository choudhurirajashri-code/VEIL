namespace VeilGeoFenceService;

/// <summary>
/// Core conditional logic that determines whether an employee is INSIDE the
/// campus and which floor/zone they are on using pure WiFi RSSI.
/// 
/// NO GPS is used.
/// </summary>
public class GeoFenceChecker
{
    // ─── Result Object ────────────────────────────────────────────

    /// <summary>
    /// Encapsulates the full result of a campus presence check.
    /// </summary>
    public class GeoFenceResult
    {
        public bool   IsInsideCampus        { get; }
        public float  ConfidenceScore       { get; }  // 0.0 – 1.0 (pure WiFi signal confidence)
        public int    VisibleMeshAPCount    { get; }
        public int    AverageSignalStrength { get; } // avg dBm of detected campus APs
        public List<MeshAPInfo> DetectedMeshAPs { get; }

        // Campus Zone Localization
        public string CurrentZone             { get; }
        public string NearestNodeSSID         { get; }
        public string NearestNodeBSSID        { get; }
        public int    NearestNodeSignal       { get; }
        public double EstimatedDistanceToNode { get; }

        public GeoFenceResult(
            bool isInsideCampus, float confidenceScore,
            int visibleMeshAPCount,
            int averageSignalStrength, List<MeshAPInfo> detectedMeshAPs,
            string currentZone, string nearestNodeSsid,
            string nearestNodeBssid, int nearestNodeSignal,
            double estimatedDistanceToNode)
        {
            IsInsideCampus          = isInsideCampus;
            ConfidenceScore         = confidenceScore;
            VisibleMeshAPCount      = visibleMeshAPCount;
            AverageSignalStrength   = averageSignalStrength;
            DetectedMeshAPs         = detectedMeshAPs;
            CurrentZone             = currentZone;
            NearestNodeSSID         = nearestNodeSsid;
            NearestNodeBSSID        = nearestNodeBssid;
            NearestNodeSignal       = nearestNodeSignal;
            EstimatedDistanceToNode = estimatedDistanceToNode;
        }

        public override string ToString()
        {
            return $"GeoFenceResult{{" +
                   $"IsInsideCampus={IsInsideCampus}" +
                   $", Zone='{CurrentZone}'" +
                   $", Confidence={ConfidenceScore:F2}" +
                   $", CampusAPs={VisibleMeshAPCount}" +
                   $", AvgSignal={AverageSignalStrength}dBm" +
                   $", Nearest={NearestNodeSSID} ({NearestNodeSignal}dBm, ~{EstimatedDistanceToNode:F1}m)" +
                   $"}}";
        }
    }

    /// <summary>
    /// Info about a single detected mesh AP.
    /// </summary>
    public class MeshAPInfo
    {
        public int    NodeId             { get; }
        public string ZoneName           { get; }
        public string SSID               { get; }
        public string IpAddress          { get; }
        public string BSSID              { get; }
        public int    SignalStrengthDbm  { get; }
        public int    Frequency          { get; }
        public double EstimatedDistance  { get; }

        public MeshAPInfo(int nodeId, string zoneName, string ssid, string ipAddress,
                          string bssid, int signalStrengthDbm, int frequency, double estimatedDistance)
        {
            NodeId            = nodeId;
            ZoneName          = zoneName;
            SSID              = ssid;
            IpAddress         = ipAddress;
            BSSID             = bssid;
            SignalStrengthDbm = signalStrengthDbm;
            Frequency         = frequency;
            EstimatedDistance = estimatedDistance;
        }
    }

    public static double EstimateDistance(int rssiDbm)
    {
        if (rssiDbm == 0) return 999.0;
        double ratio = (-45.0 - rssiDbm) / (10.0 * 2.7);
        double dist = Math.Pow(10.0, ratio);
        return Math.Round(dist, 1);
    }

    // ─── Known Mesh BSSIDs (preloaded into a HashSet for O(1) lookup) ──
    private readonly HashSet<string> _knownMeshBSSIDs;

    public GeoFenceChecker()
    {
        _knownMeshBSSIDs = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (string bssid in GeoFenceConfig.OFFICE_MESH_BSSIDS)
        {
            _knownMeshBSSIDs.Add(bssid);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  MAIN CONDITIONAL CHECK (PURE WIFI RSSI)
    // ═══════════════════════════════════════════════════════════════

    public GeoFenceResult CheckIfInsideGeofence(List<WifiScanResult>? wifiScanResults)
    {
        int   visibleMeshAPCount   = 0;
        int   strongAPCount        = 0;
        var   detectedMeshAPs      = new List<MeshAPInfo>();

        GeoFenceConfig.CampusNode? strongestNode = null;
        int strongestSignal = -999;
        double nearestDistance = 999.0;
        string? nearestBssid = null;
        string? nearestSsid = null;

        if (wifiScanResults != null && wifiScanResults.Count > 0)
        {
            foreach (var scanResult in wifiScanResults)
            {
                var matchedNode = GeoFenceConfig.FindNodeByBssidOrSsid(scanResult.BSSID, scanResult.SSID);
                bool isMesh = (matchedNode != null) || IsKnownMeshBSSID(scanResult.BSSID);

                if (isMesh && scanResult.SignalStrengthDbm >= GeoFenceConfig.MIN_WIFI_SIGNAL_STRENGTH_DBM)
                {
                    visibleMeshAPCount++;
                    double estDist = EstimateDistance(scanResult.SignalStrengthDbm);

                    int nodeId = matchedNode?.NodeId ?? 0;
                    string zone = matchedNode?.ZoneName ?? "CAMPUS_AP";
                    string ssid = matchedNode?.SSID ?? (scanResult.SSID ?? "UNKNOWN");
                    string ip = matchedNode?.IpAddress ?? "192.168.4.1";

                    detectedMeshAPs.Add(new MeshAPInfo(
                        nodeId,
                        zone,
                        ssid,
                        ip,
                        scanResult.BSSID,
                        scanResult.SignalStrengthDbm,
                        scanResult.Frequency,
                        estDist
                    ));

                    if (scanResult.SignalStrengthDbm > strongestSignal)
                    {
                        strongestSignal = scanResult.SignalStrengthDbm;
                        strongestNode   = matchedNode;
                        nearestDistance = estDist;
                        nearestBssid    = scanResult.BSSID;
                        nearestSsid     = ssid;
                    }

                    if (scanResult.SignalStrengthDbm >= GeoFenceConfig.STRONG_SIGNAL_THRESHOLD_DBM)
                    {
                        strongAPCount++;
                    }
                }
            }
        }

        // ─── Pure WiFi Confidence Scoring ─────────────────────────
        float confidenceScore = 0.0f;
        if (visibleMeshAPCount >= GeoFenceConfig.MIN_VISIBLE_MESH_APS)
        {
            confidenceScore = 0.6f;
            int extraAPs = visibleMeshAPCount - GeoFenceConfig.MIN_VISIBLE_MESH_APS;
            confidenceScore += Math.Min(extraAPs * 0.15f, 0.2f);
            confidenceScore += Math.Min(strongAPCount * 0.10f, 0.2f);
        }
        confidenceScore = Math.Min(confidenceScore, 1.0f);

        int avgSignalStrength = GetAverageSignalStrength(detectedMeshAPs);

        bool isInsideCampus = (visibleMeshAPCount >= GeoFenceConfig.MIN_VISIBLE_MESH_APS)
                              && (confidenceScore >= GeoFenceConfig.CONFIDENCE_THRESHOLD);

        // ─── Determine Employee Zone / Location ───────────────────
        string currentZone;
        if (strongestNode != null)
        {
            currentZone = strongestNode.ZoneName;
        }
        else if (isInsideCampus)
        {
            currentZone = "CAMPUS (DETECTED)";
        }
        else
        {
            currentZone = "OUTSIDE CAMPUS";
        }

        return new GeoFenceResult(
            isInsideCampus, confidenceScore,
            visibleMeshAPCount,
            avgSignalStrength, detectedMeshAPs,
            currentZone,
            nearestSsid ?? "--",
            nearestBssid ?? "--",
            strongestSignal != -999 ? strongestSignal : -100,
            nearestDistance
        );
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPER METHODS
    // ═══════════════════════════════════════════════════════════════

    private bool IsKnownMeshBSSID(string? bssid)
    {
        if (bssid == null) return false;
        return _knownMeshBSSIDs.Contains(bssid);
    }

    private static int GetAverageSignalStrength(List<MeshAPInfo> meshAPs)
    {
        if (meshAPs.Count == 0) return -100;
        int total = 0;
        foreach (var ap in meshAPs) total += ap.SignalStrengthDbm;
        return total / meshAPs.Count;
    }

    public bool IsEmployeeInsideCampus(List<WifiScanResult>? wifiScanResults)
    {
        return CheckIfInsideGeofence(wifiScanResults).IsInsideCampus;
    }
}
