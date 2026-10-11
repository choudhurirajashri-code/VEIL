using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace VeilGeoFenceService;

/// <summary>
/// Background service that continuously monitors the employee's campus presence
/// and floor location using pure WiFi RSSI signal scanning.
/// 
/// NO GPS is used.
/// </summary>
public class GeoFenceTrackingService : BackgroundService
{
    private readonly ILogger<GeoFenceTrackingService> _logger;
    private readonly GeoFenceChecker _geoFenceChecker;
    private readonly WifiScanner _wifiScanner;

    // Current state
    private List<WifiScanResult>? _latestScanResults;
    private GeoFenceChecker.GeoFenceResult? _lastResult;

    /// <summary>
    /// Event fired whenever a campus presence check completes.
    /// The system tray icon subscribes to this to update the UI.
    /// </summary>
    public event Action<GeoFenceChecker.GeoFenceResult>? OnGeoFenceUpdate;

    public GeoFenceTrackingService(
        ILogger<GeoFenceTrackingService> logger,
        GeoFenceChecker geoFenceChecker,
        WifiScanner wifiScanner)
    {
        _logger          = logger;
        _geoFenceChecker = geoFenceChecker;
        _wifiScanner     = wifiScanner;
    }

    /// <summary>
    /// Gets the last check result (for UI queries).
    /// </summary>
    public GeoFenceChecker.GeoFenceResult? LastResult => _lastResult;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation("GeoFenceTrackingService started (Pure WiFi RSSI Mode)");

        // Initial delay — let other services start
        await Task.Delay(2000, stoppingToken);

        // Start the WiFi scan loop and check loop concurrently
        var wifiScanTask = RunWifiScanLoopAsync(stoppingToken);
        var geoCheckTask = RunGeoFenceCheckLoopAsync(stoppingToken);

        await Task.WhenAll(wifiScanTask, geoCheckTask);

        _logger.LogInformation("GeoFenceTrackingService stopped");
    }

    // ═══════════════════════════════════════════════════════════════
    //  WIFI SCAN LOOP
    // ═══════════════════════════════════════════════════════════════

    private async Task RunWifiScanLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                _latestScanResults = await _wifiScanner.ScanNetworksAsync();
                _logger.LogDebug(
                    "WiFi scan completed. Found {Count} networks",
                    _latestScanResults.Count);
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "WiFi scan failed, using cached results");
            }

            await Task.Delay(GeoFenceConfig.WIFI_SCAN_INTERVAL_MS, ct);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  PRESENCE CHECK LOOP (PURE WIFI RSSI EVALUATION)
    // ═══════════════════════════════════════════════════════════════

    private async Task RunGeoFenceCheckLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                PerformGeoFenceCheck();
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Campus presence check failed");
            }

            await Task.Delay(GeoFenceConfig.GEOFENCE_CHECK_INTERVAL_MS, ct);
        }
    }

    private void PerformGeoFenceCheck()
    {
        // Pure WiFi scan-based detection (NO GPS)
        var result = _geoFenceChecker.CheckIfInsideGeofence(_latestScanResults);
        _lastResult = result;

        // Log the result
        _logger.LogInformation("─────────────────────────────────────");
        _logger.LogInformation("WiFi Presence Result:");
        _logger.LogInformation("  Inside Campus:  {Inside}", result.IsInsideCampus);
        _logger.LogInformation("  Confidence:     {Confidence:F2}", result.ConfidenceScore);
        _logger.LogInformation("  Campus APs:     {APs}", result.VisibleMeshAPCount);
        _logger.LogInformation("  Avg Signal:     {Signal} dBm", result.AverageSignalStrength);
        _logger.LogInformation("  Zone/Location:  {Zone}", result.CurrentZone);
        _logger.LogInformation("  Nearest AP:     {AP} ({Signal} dBm, ~{Distance:F1}m)",
            result.NearestNodeSSID, result.NearestNodeSignal, result.EstimatedDistanceToNode);
        _logger.LogInformation("─────────────────────────────────────");

        // Fire event for UI
        OnGeoFenceUpdate?.Invoke(result);
    }
}
