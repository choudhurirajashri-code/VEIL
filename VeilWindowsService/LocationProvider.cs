using System.Diagnostics;
using System.Net.Http.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Logging;

namespace VeilGeoFenceService;

/// <summary>
/// GPS/Location provider for Windows.
/// 
/// This is the Windows equivalent of Android's FusedLocationProviderClient.
/// 
/// Uses the Windows.Devices.Geolocation API (via WinRT interop) as the primary
/// source, with fallback to IP-based geolocation services.
/// 
/// STRATEGY:
/// ─────────
///  1. Try Windows Location API (uses GPS hardware, WiFi positioning, cell towers)
///  2. Fallback to IP-based geolocation (less accurate but always available)
/// </summary>
public class LocationProvider : IDisposable
{
    private readonly ILogger<LocationProvider> _logger;
    private readonly HttpClient _httpClient;
    private bool _disposed;

    // Cached location
    private double? _lastLatitude;
    private double? _lastLongitude;
    private float?  _lastAccuracy;
    private DateTime _lastUpdateTime = DateTime.MinValue;

    public LocationProvider(ILogger<LocationProvider> logger)
    {
        _logger = logger;
        _httpClient = new HttpClient
        {
            Timeout = TimeSpan.FromSeconds(10)
        };
    }

    /// <summary>
    /// Gets the current device location.
    /// Returns (latitude, longitude, accuracy_meters) or nulls if unavailable.
    /// </summary>
    public async Task<(double? Latitude, double? Longitude, float? Accuracy)> GetLocationAsync()
    {
        // Try Windows Location Services first
        try
        {
            var result = await GetWindowsLocationAsync();
            if (result.Latitude.HasValue)
            {
                _lastLatitude = result.Latitude;
                _lastLongitude = result.Longitude;
                _lastAccuracy = result.Accuracy;
                _lastUpdateTime = DateTime.UtcNow;

                _logger.LogDebug(
                    "GPS updated: {Lat}, {Lon} (accuracy: {Acc}m)",
                    result.Latitude, result.Longitude, result.Accuracy);

                return result;
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Windows Location API failed, trying fallback");
        }

        // Fallback: IP-based geolocation
        try
        {
            var result = await GetIpBasedLocationAsync();
            if (result.Latitude.HasValue)
            {
                _lastLatitude = result.Latitude;
                _lastLongitude = result.Longitude;
                _lastAccuracy = result.Accuracy;
                _lastUpdateTime = DateTime.UtcNow;

                _logger.LogDebug(
                    "IP-based location: {Lat}, {Lon} (accuracy: ~{Acc}m)",
                    result.Latitude, result.Longitude, result.Accuracy);

                return result;
            }
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "IP-based geolocation also failed");
        }

        // Return cached location if recent (< 2 minutes old)
        if (_lastLatitude.HasValue && (DateTime.UtcNow - _lastUpdateTime).TotalMinutes < 2)
        {
            _logger.LogDebug("Using cached location (age: {Age}s)",
                (DateTime.UtcNow - _lastUpdateTime).TotalSeconds);
            return (_lastLatitude, _lastLongitude, _lastAccuracy);
        }

        _logger.LogWarning("No location available from any source");
        return (null, null, null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  WINDOWS LOCATION API (via PowerShell / WinRT)
    // ═══════════════════════════════════════════════════════════════

    /// <summary>
    /// Uses PowerShell to invoke the Windows.Devices.Geolocation API.
    /// This works on Windows 10/11 when Location Services are enabled.
    /// </summary>
    private async Task<(double? Latitude, double? Longitude, float? Accuracy)> GetWindowsLocationAsync()
    {
        // PowerShell script to get Windows location
        string psScript = @"
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]
Function Await($WinRtTask, $ResultType) {
    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($WinRtTask))
    $netTask.Wait(-1) | Out-Null
    $netTask.Result
}
$geolocator = New-Object Windows.Devices.Geolocation.Geolocator
$geolocator.DesiredAccuracyInMeters = 10
try {
    $pos = Await ($geolocator.GetGeopositionAsync()) ([Windows.Devices.Geolocation.Geoposition])
    $coord = $pos.Coordinate.Point.Position
    Write-Output ""$($coord.Latitude)|$($coord.Longitude)|$($pos.Coordinate.Accuracy)""
} catch {
    Write-Output ""ERROR|$($_.Exception.Message)""
}
";

        using var process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = "powershell",
                Arguments = $"-NoProfile -NonInteractive -Command \"{psScript.Replace("\"", "\\\"")}\"",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            }
        };

        process.Start();

        string output = await process.StandardOutput.ReadToEndAsync();
        await process.WaitForExitAsync();

        output = output.Trim();

        if (string.IsNullOrEmpty(output) || output.StartsWith("ERROR"))
        {
            _logger.LogDebug("Windows Location API returned: {Output}", output);
            return (null, null, null);
        }

        var parts = output.Split('|');
        if (parts.Length >= 3
            && double.TryParse(parts[0], out double lat)
            && double.TryParse(parts[1], out double lon)
            && float.TryParse(parts[2], out float acc))
        {
            return (lat, lon, acc);
        }

        return (null, null, null);
    }

    // ═══════════════════════════════════════════════════════════════
    //  IP-BASED GEOLOCATION FALLBACK
    // ═══════════════════════════════════════════════════════════════

    /// <summary>
    /// Fallback: Uses a free IP geolocation API.
    /// Less accurate (~1-5km) but always available as long as internet is up.
    /// </summary>
    private async Task<(double? Latitude, double? Longitude, float? Accuracy)> GetIpBasedLocationAsync()
    {
        try
        {
            var response = await _httpClient.GetFromJsonAsync<IpGeoResponse>(
                "http://ip-api.com/json/?fields=lat,lon,status");

            if (response != null && response.Status == "success")
            {
                // IP-based geolocation has ~1-5km accuracy
                return (response.Lat, response.Lon, 2000f);
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug(ex, "IP geolocation request failed");
        }

        return (null, null, null);
    }

    private class IpGeoResponse
    {
        [JsonPropertyName("status")]
        public string Status { get; set; } = string.Empty;

        [JsonPropertyName("lat")]
        public double Lat { get; set; }

        [JsonPropertyName("lon")]
        public double Lon { get; set; }
    }

    public void Dispose()
    {
        if (!_disposed)
        {
            _httpClient.Dispose();
            _disposed = true;
        }
    }
}
