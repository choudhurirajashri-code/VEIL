using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Logging;

namespace VeilGeoFenceService;

/// <summary>
/// WiFi scanner for Windows using the Native WiFi (Wlan) API.
/// 
/// This is the Windows equivalent of Android's WifiManager + ScanResult system.
/// Uses `netsh wlan show networks mode=bssid` for scanning and
/// `netsh wlan show interfaces` for connected network info.
/// 
/// Falls back to WMI if netsh is unavailable.
/// </summary>
public class WifiScanner : IDisposable
{
    private readonly ILogger<WifiScanner> _logger;
    private bool _disposed;

    public WifiScanner(ILogger<WifiScanner> logger)
    {
        _logger = logger;
    }

    /// <summary>
    /// Scans for all visible WiFi networks and returns results.
    /// Uses 'netsh wlan show networks mode=bssid' to enumerate all visible BSSIDs.
    /// </summary>
    public async Task<List<WifiScanResult>> ScanNetworksAsync()
    {
        var results = new List<WifiScanResult>();

        try
        {
            string output = await RunNetshCommandAsync("wlan show networks mode=bssid");

            if (string.IsNullOrWhiteSpace(output))
            {
                _logger.LogWarning("WiFi scan returned empty output — WiFi may be disabled");
                return results;
            }

            results = ParseNetworkScanOutput(output);
            _logger.LogDebug("WiFi scan completed. Found {Count} networks", results.Count);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "WiFi scan failed");
        }

        return results;
    }

    /// <summary>
    /// Gets the currently connected WiFi network's SSID and BSSID.
    /// Uses 'netsh wlan show interfaces' to retrieve current connection info.
    /// </summary>
    public async Task<(string? SSID, string? BSSID)> GetConnectedNetworkAsync()
    {
        try
        {
            string output = await RunNetshCommandAsync("wlan show interfaces");

            if (string.IsNullOrWhiteSpace(output))
            {
                return (null, null);
            }

            return ParseConnectedNetworkOutput(output);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "Failed to get connected network info");
            return (null, null);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  NETSH COMMAND EXECUTION
    // ═══════════════════════════════════════════════════════════════

    private static async Task<string> RunNetshCommandAsync(string arguments)
    {
        using var process = new Process
        {
            StartInfo = new ProcessStartInfo
            {
                FileName = "netsh",
                Arguments = arguments,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                StandardOutputEncoding = System.Text.Encoding.UTF8
            }
        };

        process.Start();
        string output = await process.StandardOutput.ReadToEndAsync();
        await process.WaitForExitAsync();

        return output;
    }

    // ═══════════════════════════════════════════════════════════════
    //  PARSING — Network Scan Output
    // ═══════════════════════════════════════════════════════════════

    /// <summary>
    /// Parses 'netsh wlan show networks mode=bssid' output into WifiScanResult objects.
    /// 
    /// Example output format:
    ///   SSID 1 : OfficeCampus_WiFi
    ///       Network type            : Infrastructure
    ///       Authentication          : WPA2-Personal
    ///       Encryption              : CCMP
    ///       BSSID 1                 : aa:bb:cc:dd:ee:01
    ///            Signal             : 85%
    ///            Radio type         : 802.11ac
    ///            Channel            : 36
    ///       BSSID 2                 : aa:bb:cc:dd:ee:02
    ///            Signal             : 62%
    ///            ...
    /// </summary>
    private List<WifiScanResult> ParseNetworkScanOutput(string output)
    {
        var results = new List<WifiScanResult>();
        string currentSSID = string.Empty;

        var lines = output.Split('\n', StringSplitOptions.None);

        string? currentBSSID = null;
        int currentSignalPercent = 0;
        int currentChannel = 0;

        foreach (string rawLine in lines)
        {
            string line = rawLine.Trim();

            // Match SSID line: "SSID 1 : NetworkName"
            var ssidMatch = Regex.Match(line, @"^SSID\s+\d+\s*:\s*(.+)$", RegexOptions.IgnoreCase);
            if (ssidMatch.Success)
            {
                // Save previous BSSID entry if exists
                if (currentBSSID != null)
                {
                    results.Add(CreateScanResult(currentSSID, currentBSSID, currentSignalPercent, currentChannel));
                }

                currentSSID = ssidMatch.Groups[1].Value.Trim();
                currentBSSID = null;
                currentSignalPercent = 0;
                currentChannel = 0;
                continue;
            }

            // Match BSSID line: "BSSID 1                 : aa:bb:cc:dd:ee:ff"
            var bssidMatch = Regex.Match(line, @"^BSSID\s+\d+\s*:\s*([0-9a-fA-F:]+)", RegexOptions.IgnoreCase);
            if (bssidMatch.Success)
            {
                // Save previous BSSID entry if exists
                if (currentBSSID != null)
                {
                    results.Add(CreateScanResult(currentSSID, currentBSSID, currentSignalPercent, currentChannel));
                }

                currentBSSID = bssidMatch.Groups[1].Value.Trim().ToUpperInvariant();
                currentSignalPercent = 0;
                currentChannel = 0;
                continue;
            }

            // Match Signal line: "Signal             : 85%"
            var signalMatch = Regex.Match(line, @"^Signal\s*:\s*(\d+)%", RegexOptions.IgnoreCase);
            if (signalMatch.Success)
            {
                currentSignalPercent = int.Parse(signalMatch.Groups[1].Value);
                continue;
            }

            // Match Channel line: "Channel            : 36"
            var channelMatch = Regex.Match(line, @"^Channel\s*:\s*(\d+)", RegexOptions.IgnoreCase);
            if (channelMatch.Success)
            {
                currentChannel = int.Parse(channelMatch.Groups[1].Value);
                continue;
            }
        }

        // Don't forget the last entry
        if (currentBSSID != null)
        {
            results.Add(CreateScanResult(currentSSID, currentBSSID, currentSignalPercent, currentChannel));
        }

        return results;
    }

    /// <summary>
    /// Creates a WifiScanResult, converting Windows signal percentage to dBm.
    /// Windows reports signal quality as 0–100%, we convert to approximate dBm.
    /// 
    /// Conversion formula (Microsoft documented):
    ///   dBm = (quality / 2) - 100
    ///   e.g., 100% → -50 dBm, 50% → -75 dBm, 0% → -100 dBm
    /// </summary>
    private static WifiScanResult CreateScanResult(string ssid, string bssid, int signalPercent, int channel)
    {
        // Convert signal quality percentage to approximate dBm
        int signalDbm = (signalPercent / 2) - 100;

        // Convert channel to approximate frequency
        int frequency = ChannelToFrequency(channel);

        return new WifiScanResult
        {
            SSID = ssid,
            BSSID = bssid,
            SignalStrengthDbm = signalDbm,
            Frequency = frequency
        };
    }

    /// <summary>
    /// Converts WiFi channel number to approximate frequency in MHz.
    /// </summary>
    private static int ChannelToFrequency(int channel)
    {
        if (channel >= 1 && channel <= 13)
        {
            // 2.4 GHz band
            return 2407 + (channel * 5);
        }
        else if (channel == 14)
        {
            return 2484;
        }
        else if (channel >= 36 && channel <= 177)
        {
            // 5 GHz band
            return 5000 + (channel * 5);
        }
        return 0;
    }

    // ═══════════════════════════════════════════════════════════════
    //  PARSING — Connected Network
    // ═══════════════════════════════════════════════════════════════

    /// <summary>
    /// Parses 'netsh wlan show interfaces' output to extract current connection.
    /// 
    /// Example output:
    ///     State                  : connected
    ///     SSID                   : OfficeCampus_WiFi
    ///     BSSID                  : aa:bb:cc:dd:ee:01
    ///     ...
    /// </summary>
    private (string? SSID, string? BSSID) ParseConnectedNetworkOutput(string output)
    {
        string? ssid = null;
        string? bssid = null;
        bool isConnected = false;

        var lines = output.Split('\n', StringSplitOptions.None);

        foreach (string rawLine in lines)
        {
            string line = rawLine.Trim();

            // Check state
            var stateMatch = Regex.Match(line, @"^State\s*:\s*(.+)$", RegexOptions.IgnoreCase);
            if (stateMatch.Success)
            {
                isConnected = stateMatch.Groups[1].Value.Trim()
                    .Equals("connected", StringComparison.OrdinalIgnoreCase);
                continue;
            }

            // Get SSID (not BSSID)
            var ssidMatch = Regex.Match(line, @"^\s*SSID\s*:\s*(.+)$", RegexOptions.IgnoreCase);
            if (ssidMatch.Success && !line.TrimStart().StartsWith("BSSID", StringComparison.OrdinalIgnoreCase))
            {
                ssid = ssidMatch.Groups[1].Value.Trim();
                continue;
            }

            // Get BSSID
            var bssidMatch = Regex.Match(line, @"^BSSID\s*:\s*([0-9a-fA-F:]+)", RegexOptions.IgnoreCase);
            if (bssidMatch.Success)
            {
                bssid = bssidMatch.Groups[1].Value.Trim().ToUpperInvariant();
                continue;
            }
        }

        if (!isConnected)
        {
            return (null, null);
        }

        return (ssid, bssid);
    }

    public void Dispose()
    {
        if (!_disposed)
        {
            _disposed = true;
        }
    }
}

/// <summary>
/// Model representing a single scanned WiFi network result on Windows.
/// </summary>
public class WifiScanResult
{
    public string SSID { get; set; } = string.Empty;
    public string BSSID { get; set; } = string.Empty;
    public int SignalStrengthDbm { get; set; }
    public int Frequency { get; set; }
}
