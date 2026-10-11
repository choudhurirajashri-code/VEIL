using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace VeilGeoFenceService;

/// <summary>
/// Background service that continuously monitors the employee's location using
/// GPS and WiFi mesh signals, and determines whether they are inside the
/// geo-fenced office campus.
/// 
/// DIRECT PORT of GeoFenceTrackingService.java from the Android project.
/// 
/// Runs as a Windows Background Service (IHostedService).
/// 
/// ARCHITECTURE:
/// ─────────────
///   [GPS via Windows Location API]     ──┐
///                                        ├──→ GeoFenceChecker → Event / Notification
///   [WiFi scan via netsh/WlanAPI]      ──┘
///                                           ↓
///                                     TrayIcon receives
///                                     event & updates tooltip/balloon
/// </summary>
public class GeoFenceTrackingService : BackgroundService
{
    private readonly ILogger<GeoFenceTrackingService> _logger;
    private readonly GeoFenceChecker _geoFenceChecker;
    private readonly WifiScanner _wifiScanner;
    private readonly LocationProvider _locationProvider;

    // Current state
    private List<WifiScanResult>? _latestScanResults;
    private GeoFenceChecker.GeoFenceResult? _lastResult;

    /// <summary>
    /// Event fired whenever a geo-fence check completes.
    /// The system tray icon subscribes to this to update the UI.
    /// </summary>
    public event Action<GeoFenceChecker.GeoFenceResult>? OnGeoFenceUpdate;

    public GeoFenceTrackingService(
        ILogger<GeoFenceTrackingService> logger,
        GeoFenceChecker geoFenceChecker,
        WifiScanner wifiScanner,
        LocationProvider locationProvider)
    {
        _logger           = logger;
        _geoFenceChecker  = geoFenceChecker;
        _wifiScanner      = wifiScanner;
        _locationProvider = locationProvider;
    }

    /// <summary>
    /// Gets the last geo-fence check result (for UI queries).
    /// </summary>
    public GeoFenceChecker.GeoFenceResult? LastResult => _lastResult;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        _logger.LogInformation("GeoFenceTrackingService started");

        // Initial delay — let other services start
        await Task.Delay(2000, stoppingToken);

        // Start the WiFi scan loop and geo-fence check loop concurrently
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
    //  GEO-FENCE CHECK LOOP (THE CORE CONDITIONAL EVALUATION)
    // ═══════════════════════════════════════════════════════════════

    private async Task RunGeoFenceCheckLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await PerformGeoFenceCheckAsync();
            }
            catch (Exception ex)
            {
                _logger.LogError(ex, "Geo-fence check failed");
            }

            await Task.Delay(GeoFenceConfig.GEOFENCE_CHECK_INTERVAL_MS, ct);
        }
    }

    private async Task PerformGeoFenceCheckAsync()
    {
        // Get current GPS location
        var (latitude, longitude, accuracy) = await _locationProvider.GetLocationAsync();

        // ★ INVOKE THE GEO-FENCE CHECKER (SCAN-ONLY) ★
        // No WiFi connection info needed — purely scan-based detection
        var result = _geoFenceChecker.CheckIfInsideGeofence(
            latitude,
            longitude,
            accuracy,
            _latestScanResults
        );

        _lastResult = result;

        // Log the result (mirrors Android's log format exactly)
        _logger.LogInformation("─────────────────────────────────────");
        _logger.LogInformation("GeoFence Check Result:");
        _logger.LogInformation("  Inside Campus:  {Inside}", result.IsInsideCampus);
        _logger.LogInformation("  Confidence:     {Confidence:F2}", result.ConfidenceScore);
        _logger.LogInformation("  GPS Score:      {GPS:F2}", result.GpsScore);
        _logger.LogInformation("  WiFi Score:     {WiFi:F2}", result.WifiScore);
        _logger.LogInformation("  Distance:       {Distance:F1}m", result.DistanceFromCenter);
        _logger.LogInformation("  Mesh APs:       {APs}", result.VisibleMeshAPCount);
        _logger.LogInformation("  Avg Signal:     {Signal} dBm", result.AverageSignalStrength);
        _logger.LogInformation("─────────────────────────────────────");

        // Fire event for UI (equivalent of Android's LocalBroadcast)
        OnGeoFenceUpdate?.Invoke(result);
    }
}
