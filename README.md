# MeshCore WearOS Client

A native **Wear OS** client for the [MeshCore](https://meshcore.co.uk) LoRa mesh radio network, built with Jetpack Compose for Wear OS and Kotlin.

## Overview

MeshCore WearOS allows you to communicate over a MeshCore LoRa mesh network directly from your smartwatch. It connects to a MeshCore companion radio device via **Bluetooth Low Energy (BLE)** using the Nordic UART Service (NUS) protocol.

## Features

| Feature | Status |
|---|---|
| BLE device scanning & connection | ✅ |
| Contact list synchronization | ✅ |
| Direct messaging (DM) | ✅ |
| Quick reply with preset messages | ✅ |
| Message delivery confirmation (ACK) | ✅ |
| Push notifications for new messages | ✅ |
| Battery & storage monitoring | ✅ |
| Self-advertisement broadcast | ✅ |
| Wear OS Tile for quick status | ✅ |
| Watch Face Complication | ✅ |
| Foreground BLE service | ✅ |
| Channel messaging | 🚧 |
| Node map view | 🚧 |
| Repeater CLI access | 🚧 |

## Architecture

The app follows a clean architecture pattern with the following layers:

```
UI Layer (Jetpack Compose for Wear OS)
    ↕
ViewModel Layer (Hilt + StateFlow)
    ↕
Repository Layer (MeshCoreRepository)
    ↕
BLE Transport (BleManager + FrameParser)   ←→   MeshCore Companion Radio
    ↕
Persistence Layer (Room Database)
```

### Key Components

| Component | Description |
|---|---|
| `BleManager` | Handles BLE scanning, GATT connection, and characteristic I/O |
| `MeshCoreProtocol` | Defines all protocol constants and frame builders |
| `FrameParser` | Decodes raw BLE frames into typed `ParsedFrame` objects |
| `MeshCoreRepository` | Coordinates BLE, parsing, and database operations |
| `MeshCoreBleService` | Foreground service for background BLE connectivity |
| `MeshCoreTileService` | Wear OS Tile showing connection status |
| `MeshCoreComplicationService` | Watch face complication data source |

## Protocol

This app implements the [MeshCore Companion Radio Protocol](https://github.com/meshcore-dev/MeshCore/wiki/Companion-Radio-Protocol) (Protocol Version 3).

Communication uses the **Nordic UART Service (NUS)** over BLE:
- **RX Characteristic** (`6E400002-...`): App → Radio (write without response)
- **TX Characteristic** (`6E400003-...`): Radio → App (notify)

Each BLE characteristic value is a single complete protocol frame. The first byte is the command or response code.

## Requirements

- **Wear OS 3.0+** (API level 30+)
- A MeshCore companion radio device flashed with the **BLE companion firmware**
- Android Studio Ladybug or newer for building

## Building

```bash
# Clone the repository
git clone https://github.com/thegroovex/meshcore-wearos.git
cd meshcore-wearos

# Build debug APK
./gradlew assembleDebug

# Install on connected Wear OS device
./gradlew installDebug
```

## Supported Hardware

Any MeshCore-compatible LoRa device flashed with the BLE companion firmware, including:

- LILYGO T-Echo
- Heltec LoRa 32 V3
- RAK WisBlock (with BLE module)
- Seeed XIAO nRF52840 + LoRa module

See the [MeshCore device list](https://meshcore.co.uk/get.html) for the full list of supported hardware.

## Dependencies

| Library | Purpose |
|---|---|
| Jetpack Compose for Wear OS | UI framework |
| Wear Compose Material | Wear OS Material Design components |
| Horologist | Google's Wear OS utility library |
| Hilt | Dependency injection |
| Room | Local SQLite database |
| Kotlin Coroutines | Asynchronous programming |
| DataStore | Preferences storage |

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

## Related Projects

- [MeshCore Firmware](https://github.com/meshcore-dev/MeshCore) — The core MeshCore library and companion firmware
- [meshcore-open](https://github.com/zjs81/meshcore-open) — Open-source Flutter client for Android/iOS
- [MeshCore Web Client](https://client.meshcore.co.uk) — Browser-based client
