# VEIL — Office Campus Geo-Fence Tracker (Windows)

> **Windows background service + system tray app** — a direct port of the Android VEIL project.  
> Monitors employee campus presence using GPS + WiFi Mesh signal fusion.

---

## 📐 Architecture Mapping (Android → Windows)

| Android Component | Windows Equivalent |
|---|---|
| `GeoFenceConfig.java` | `GeoFenceConfig.cs` — identical constants & thresholds |
| `GeoFenceChecker.java` | `GeoFenceChecker.cs` — identical algorithm & 3-condition logic |
| `GeoFenceTrackingService.java` (Foreground Service) | `GeoFenceTrackingService.cs` (.NET BackgroundService) |
| `MainActivity.java` (UI + BroadcastReceiver) | `TrayApplication.cs` (System Tray + Event handler) |
| `FusedLocationProviderClient` | `LocationProvider.cs` (Windows Location API + IP fallback) |
| `WifiManager` + `ScanResult` | `WifiScanner.cs` (netsh wlan commands) |
| `LocalBroadcastManager` | C# `event Action<GeoFenceResult>` |
| Foreground Notification | System Tray Icon (green/red) + Balloon notifications |
| `AndroidManifest.xml` permissions | `app.manifest` (DPI-aware) |

## 🚀 Quick Start

### Prerequisites
- Windows 10/11
- [.NET 8 SDK](https://dotnet.microsoft.com/download/dotnet/8.0)

### Build & Run (Interactive / Tray Mode)
```powershell
cd VeilWindowsService
dotnet restore
dotnet build
dotnet run
```
A green/red circle icon will appear in the system tray.

### Install as Windows Service (Headless)
```powershell
# Build release
dotnet publish -c Release -o ./publish

# Install as Windows Service (run as Administrator)
sc create VeilGeoFence binPath= "D:\VEIL\VeilWindowsService\publish\VeilGeoFenceService.exe --service"
sc start VeilGeoFence

# To stop/remove:
sc stop VeilGeoFence
sc delete VeilGeoFence
```

## 🎯 How It Works

The same **3-condition algorithm** from the Android project:

```
CONDITION 1: Combined confidence score >= 60%
    confidence = (0.4 × GPS_score) + (0.6 × WiFi_score)

CONDITION 2: GPS inside radius AND connected to mesh WiFi
    (dual confirmation override)

CONDITION 3: Connected to mesh + sees ≥3 strong APs
    (deep indoor WiFi-only override)
```

### GPS Source
1. **Primary**: Windows Location API (via `Windows.Devices.Geolocation`)
2. **Fallback**: IP-based geolocation (~2km accuracy)

### WiFi Source
Uses `netsh wlan show networks mode=bssid` to scan all visible WiFi networks, and `netsh wlan show interfaces` for the current connection. Signal percentage is converted to dBm using Microsoft's formula: `dBm = (quality / 2) - 100`.

## 📁 Project Structure

```
VeilWindowsService/
├── Program.cs                    # Entry point (tray or service mode)
├── GeoFenceConfig.cs             # All configuration constants
├── GeoFenceChecker.cs            # Core geo-fence decision algorithm
├── GeoFenceTrackingService.cs    # Background monitoring loop
├── LocationProvider.cs           # GPS/location via Windows APIs
├── WifiScanner.cs                # WiFi scanning via netsh
├── TrayApplication.cs            # System tray UI
├── VeilGeoFenceService.csproj    # .NET 8 project file
├── app.manifest                  # Windows manifest
└── Resources/
    └── icon.ico                  # Tray icon
```

## ⚙️ Configuration

Edit `GeoFenceConfig.cs` to set your office campus coordinates and mesh WiFi BSSIDs:

```csharp
public const double OFFICE_LATITUDE  = 28.6139;   // Your office latitude
public const double OFFICE_LONGITUDE = 77.2090;    // Your office longitude
public const float  GEO_FENCE_RADIUS_METERS = 200.0f;
public const string OFFICE_MESH_SSID = "OfficeCampus_WiFi";

public static readonly string[] OFFICE_MESH_BSSIDS = {
    "AA:BB:CC:DD:EE:01",  // AP-1: Lobby
    "AA:BB:CC:DD:EE:02",  // AP-2: Floor 1
    // ... add your mesh AP MAC addresses
};
```

## 🔔 System Tray Features

- **Icon color**: 🟢 Green = inside campus, 🔴 Red = outside campus
- **Tooltip**: Shows current status and confidence/distance
- **Right-click menu**: Full breakdown of GPS score, WiFi score, mesh APs, etc.
- **Double-click**: Opens detailed status dialog
- **Balloon notifications**: Fires when entering/leaving the campus
