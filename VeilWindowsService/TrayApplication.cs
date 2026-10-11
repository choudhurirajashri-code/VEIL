using Microsoft.Extensions.Logging;

namespace VeilGeoFenceService;

/// <summary>
/// System Tray application that displays the geo-fence tracking status.
/// 
/// This is the Windows equivalent of Android's MainActivity + foreground notification.
/// 
/// Shows:
///  • Tray icon that changes color based on inside/outside status
///  • Balloon notifications on status changes
///  • Right-click context menu with detailed stats
///  • Double-click opens a status detail window
/// 
/// ARCHITECTURE MAPPING:
/// ─────────────────────
///  Android Foreground Notification  →  Windows System Tray Icon
///  Android LocalBroadcast           →  C# Event subscription
///  Android MainActivity UI          →  Context menu + Detail form
/// </summary>
public class TrayApplication : ApplicationContext
{
    private readonly NotifyIcon _notifyIcon;
    private readonly GeoFenceTrackingService _trackingService;
    private readonly ILogger<TrayApplication> _logger;

    // Context menu items (updated with live data)
    private readonly ToolStripMenuItem _statusItem;
    private readonly ToolStripMenuItem _confidenceItem;
    private readonly ToolStripMenuItem _zoneItem;
    private readonly ToolStripMenuItem _nearestApItem;
    private readonly ToolStripMenuItem _wifiScoreItem;
    private readonly ToolStripMenuItem _meshAPsItem;
    private readonly ToolStripMenuItem _avgSignalItem;

    // Track last status for balloon notifications
    private bool? _lastIsInside;

    public TrayApplication(
        GeoFenceTrackingService trackingService,
        ILogger<TrayApplication> logger)
    {
        _trackingService = trackingService;
        _logger = logger;

        // Create context menu items
        _statusItem     = new ToolStripMenuItem("Status: Initializing...")     { Enabled = false };
        _zoneItem       = new ToolStripMenuItem("📍 Zone: --")                 { Enabled = false };
        _nearestApItem  = new ToolStripMenuItem("Nearest AP: --")             { Enabled = false };
        _confidenceItem = new ToolStripMenuItem("WiFi Confidence: --")        { Enabled = false };
        _wifiScoreItem  = new ToolStripMenuItem("WiFi RSSI Score: --")        { Enabled = false };
        _meshAPsItem    = new ToolStripMenuItem("Campus APs: --")             { Enabled = false };
        _avgSignalItem  = new ToolStripMenuItem("Avg Signal: --")             { Enabled = false };

        // Build context menu
        var contextMenu = new ContextMenuStrip();
        contextMenu.Items.Add(_statusItem);
        contextMenu.Items.Add(_zoneItem);
        contextMenu.Items.Add(_nearestApItem);
        contextMenu.Items.Add(new ToolStripSeparator());
        contextMenu.Items.Add(_confidenceItem);
        contextMenu.Items.Add(_wifiScoreItem);
        contextMenu.Items.Add(new ToolStripSeparator());
        contextMenu.Items.Add(_meshAPsItem);
        contextMenu.Items.Add(_avgSignalItem);
        contextMenu.Items.Add(new ToolStripSeparator());
        contextMenu.Items.Add(new ToolStripMenuItem("Exit", null, OnExitClicked));

        // Create the tray icon
        _notifyIcon = new NotifyIcon
        {
            Icon = CreateStatusIcon(false),
            Text = "Office Campus Tracker\nInitializing...",
            ContextMenuStrip = contextMenu,
            Visible = true
        };

        _notifyIcon.DoubleClick += OnTrayDoubleClick;

        // Subscribe to geo-fence updates (equivalent of Android's BroadcastReceiver)
        _trackingService.OnGeoFenceUpdate += OnGeoFenceUpdate;

        // Show startup balloon
        _notifyIcon.ShowBalloonTip(
            3000,
            "Office Campus Tracker",
            "Monitoring campus presence using GPS + WiFi Mesh...",
            ToolTipIcon.Info);

        _logger.LogInformation("System tray application initialized");
    }

    // ═══════════════════════════════════════════════════════════════
    //  GEO-FENCE UPDATE HANDLER
    //  (Equivalent of Android's BroadcastReceiver.onReceive)
    // ═══════════════════════════════════════════════════════════════

    private void OnGeoFenceUpdate(GeoFenceChecker.GeoFenceResult result)
    {
        // Must invoke on UI thread for WinForms controls
        if (_notifyIcon.ContextMenuStrip?.InvokeRequired == true)
        {
            _notifyIcon.ContextMenuStrip.Invoke(() => UpdateTrayUI(result));
        }
        else
        {
            UpdateTrayUI(result);
        }
    }

    private void UpdateTrayUI(GeoFenceChecker.GeoFenceResult result)
    {
        // ─── Update icon ──────────────────────────────────────────
        _notifyIcon.Icon = CreateStatusIcon(result.IsInsideCampus);

        // ─── Update tooltip ───────────────────────────────────────
        string statusText = result.IsInsideCampus
            ? $"✅ Inside: {result.CurrentZone} ({result.ConfidenceScore * 100:F0}%)"
            : "❌ Outside Campus (No office AP)";

        // NotifyIcon.Text has 128-char limit
        string tooltip = $"Office Campus Tracker\n{statusText}";
        _notifyIcon.Text = tooltip.Length > 127 ? tooltip[..127] : tooltip;

        // ─── Update context menu items ────────────────────────────
        _statusItem.Text = result.IsInsideCampus
            ? "✅ INSIDE CAMPUS"
            : "❌ OUTSIDE CAMPUS";

        _zoneItem.Text = $"📍 Zone: {result.CurrentZone}";
        _nearestApItem.Text = result.NearestNodeSignal > -100
            ? $"Nearest: {result.NearestNodeSSID} ({result.NearestNodeSignal} dBm)"
            : "Nearest AP: None detected";

        int confidencePercent = (int)Math.Round(result.ConfidenceScore * 100);
        _confidenceItem.Text = $"WiFi Confidence: {confidencePercent}%";
        _wifiScoreItem.Text = $"WiFi RSSI Score: {confidencePercent}%";

        _meshAPsItem.Text = $"Campus APs: {result.VisibleMeshAPCount} / {GeoFenceConfig.CAMPUS_NODES.Length}";
        _avgSignalItem.Text = $"Avg signal strength: {result.AverageSignalStrength} dBm";

        // ─── Show balloon on status CHANGE ────────────────────────
        if (_lastIsInside != result.IsInsideCampus)
        {
            string balloonTitle = result.IsInsideCampus
                ? "✅ Entered Campus"
                : "❌ Left Campus";

            string balloonText = result.IsInsideCampus
                ? $"Location: {result.CurrentZone}\nNearest AP: {result.NearestNodeSSID} ({result.NearestNodeSignal} dBm)"
                : "Outside office campus boundary";

            _notifyIcon.ShowBalloonTip(
                5000,
                balloonTitle,
                balloonText,
                result.IsInsideCampus ? ToolTipIcon.Info : ToolTipIcon.Warning);

            _lastIsInside = result.IsInsideCampus;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  ICON GENERATION
    // ═══════════════════════════════════════════════════════════════

    /// <summary>
    /// Creates a simple colored icon to indicate inside/outside status.
    /// Green circle = inside campus, Red circle = outside campus.
    /// </summary>
    private static Icon CreateStatusIcon(bool isInside)
    {
        using var bitmap = new Bitmap(32, 32);
        using var graphics = Graphics.FromImage(bitmap);

        graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
        graphics.Clear(Color.Transparent);

        // Outer ring
        using var ringBrush = new SolidBrush(isInside
            ? Color.FromArgb(46, 125, 50)     // Dark green
            : Color.FromArgb(198, 40, 40));    // Dark red

        graphics.FillEllipse(ringBrush, 1, 1, 30, 30);

        // Inner circle (lighter shade)
        using var innerBrush = new SolidBrush(isInside
            ? Color.FromArgb(76, 175, 80)     // Light green
            : Color.FromArgb(239, 83, 80));    // Light red

        graphics.FillEllipse(innerBrush, 5, 5, 22, 22);

        // Center dot (white)
        using var centerBrush = new SolidBrush(Color.White);
        graphics.FillEllipse(centerBrush, 11, 11, 10, 10);

        // Convert bitmap to icon
        IntPtr hIcon = bitmap.GetHicon();
        return Icon.FromHandle(hIcon);
    }

    // ═══════════════════════════════════════════════════════════════
    //  EVENT HANDLERS
    // ═══════════════════════════════════════════════════════════════

    private void OnTrayDoubleClick(object? sender, EventArgs e)
    {
        var result = _trackingService.LastResult;
        if (result == null)
        {
            MessageBox.Show(
                "Geo-fence tracker is still initializing...\nPlease wait a few seconds.",
                "Office Campus Tracker",
                MessageBoxButtons.OK,
                MessageBoxIcon.Information);
            return;
        }

        string details = $"""
            ══════════════════════════════════════
               OFFICE CAMPUS TRACKER — STATUS
            ══════════════════════════════════════

            Status:              {(result.IsInsideCampus ? "✅ INSIDE CAMPUS" : "❌ OUTSIDE CAMPUS")}
            Current Zone:        {result.CurrentZone}
            Nearest AP:          {result.NearestNodeSSID} ({result.NearestNodeSignal} dBm)
            Est. Distance:       ~{result.EstimatedDistanceToNode:F1}m
            WiFi Confidence:     {result.ConfidenceScore * 100:F1}%

            ── WiFi Campus Nodes (Scan-Only) ─────
            Visible Campus APs:  {result.VisibleMeshAPCount} / {GeoFenceConfig.CAMPUS_NODES.Length}
            Avg Signal Strength: {result.AverageSignalStrength} dBm

            ── Thresholds ────────────────────────
            Confidence threshold: {GeoFenceConfig.CONFIDENCE_THRESHOLD * 100}%
            Min signal strength:  {GeoFenceConfig.MIN_WIFI_SIGNAL_STRENGTH_DBM} dBm
            Min visible APs:      {GeoFenceConfig.MIN_VISIBLE_MESH_APS}

            ══════════════════════════════════════
            """;

        MessageBox.Show(
            details,
            "Office Campus Tracker — Detailed Status",
            MessageBoxButtons.OK,
            result.IsInsideCampus ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
    }

    private void OnExitClicked(object? sender, EventArgs e)
    {
        _logger.LogInformation("User requested exit from tray menu");

        _notifyIcon.Visible = false;
        _notifyIcon.Dispose();

        Application.Exit();
        Environment.Exit(0);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _trackingService.OnGeoFenceUpdate -= OnGeoFenceUpdate;
            _notifyIcon.Visible = false;
            _notifyIcon.Dispose();
        }
        base.Dispose(disposing);
    }
}
