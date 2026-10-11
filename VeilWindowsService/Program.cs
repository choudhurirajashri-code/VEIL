using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace VeilGeoFenceService;

/// <summary>
/// Entry point for the VEIL Office Campus Geo-Fence Tracker (Windows).
/// 
/// Supports two modes:
///   1. INTERACTIVE (default): Runs as a system tray application with a background service.
///   2. SERVICE MODE (--service): Runs as a Windows Service (no UI).
///      Install with: sc create VeilGeoFence binPath= "path\to\exe --service"
/// 
/// ARCHITECTURE:
/// ─────────────
///   [Program.Main]
///       ├── Creates DI container with all services
///       ├── GeoFenceTrackingService (BackgroundService)
///       │    ├── LocationProvider (GPS via Windows Location API)
///       │    ├── WifiScanner (WiFi via netsh/WlanAPI)
///       │    └── GeoFenceChecker (core decision logic)
///       └── TrayApplication (System Tray UI — interactive mode only)
/// </summary>
internal static class Program
{
    [STAThread]
    static void Main(string[] args)
    {
        bool isServiceMode = args.Contains("--service", StringComparer.OrdinalIgnoreCase);

        if (isServiceMode)
        {
            RunAsWindowsService(args);
        }
        else
        {
            RunAsInteractiveApp(args);
        }
    }

    /// <summary>
    /// Runs as an interactive system tray application.
    /// The background service runs inside the same process.
    /// </summary>
    private static void RunAsInteractiveApp(string[] args)
    {
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);

        // Build the host with DI
        var host = CreateHostBuilder(args)
            .ConfigureLogging(logging =>
            {
                logging.ClearProviders();
                logging.AddConsole();
                logging.SetMinimumLevel(LogLevel.Information);
            })
            .Build();

        // Start the host (which starts the background service)
        host.StartAsync().GetAwaiter().GetResult();

        // Get services from DI
        var trackingService = host.Services.GetRequiredService<GeoFenceTrackingService>();
        var logger = host.Services.GetRequiredService<ILogger<TrayApplication>>();

        // Run the WinForms tray application on the main thread
        var trayApp = new TrayApplication(trackingService, logger);
        Application.Run(trayApp);

        // Cleanup
        host.StopAsync().GetAwaiter().GetResult();
        host.Dispose();
    }

    /// <summary>
    /// Runs as a headless Windows Service.
    /// Install: sc create VeilGeoFence binPath= "C:\path\to\VeilGeoFenceService.exe --service"
    /// Start:   sc start VeilGeoFence
    /// Stop:    sc stop VeilGeoFence
    /// Remove:  sc delete VeilGeoFence
    /// </summary>
    private static void RunAsWindowsService(string[] args)
    {
        var host = CreateHostBuilder(args)
            .UseWindowsService(options =>
            {
                options.ServiceName = "VeilGeoFence";
            })
            .ConfigureLogging(logging =>
            {
                logging.ClearProviders();
                logging.AddEventLog(settings =>
                {
                    settings.SourceName = "VeilGeoFence";
                    settings.LogName = "Application";
                });
                logging.SetMinimumLevel(LogLevel.Information);
            })
            .Build();

        host.Run();
    }

    /// <summary>
    /// Creates the generic host builder with all services registered.
    /// </summary>
    private static IHostBuilder CreateHostBuilder(string[] args) =>
        Host.CreateDefaultBuilder(args)
            .ConfigureServices((context, services) =>
            {
                // Register core services
                services.AddSingleton<GeoFenceChecker>();
                services.AddSingleton<WifiScanner>();
                services.AddSingleton<LocationProvider>();

                // Register the background tracking service
                services.AddSingleton<GeoFenceTrackingService>();
                services.AddHostedService(sp => sp.GetRequiredService<GeoFenceTrackingService>());
            });
}
