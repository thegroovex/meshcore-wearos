# MeshCore WearOS Client

A native **Wear OS** client for the [MeshCore](https://meshcore.co.uk) LoRa mesh radio network, built with Jetpack Compose for Wear OS and Kotlin.

## Overview

MeshCore WearOS allows you to communicate over a MeshCore LoRa mesh network directly from your smartwatch. It connects to a MeshCore companion radio device via **Bluetooth Low Energy (BLE)** using the Nordic UART Service (NUS) protocol.

## Features

| Feature | Status |
|---|---|
| BLE scan, bonding with automatic PIN entry, MTU negotiation | ✅ |
| Auto-reconnect with back-off, reconnect to last radio on start | ✅ |
| Contact sync (full + incremental on adverts) | ✅ |
| Direct messages with free text (keyboard / voice) and quick replies | ✅ |
| Delivery receipts (ACK) with automatic retries, tap to resend | ✅ |
| Channel (group) messages incl. Public channel | ✅ |
| Notifications per conversation, deep link into the chat | ✅ |
| Battery, storage, firmware and radio info | ✅ |
| Zero-hop and flood adverts, route reset per contact | ✅ |
| Wear OS Tile and watch-face complication (status + unread) | ✅ |
| Foreground BLE service | ✅ |
| Node map view | 🚧 |
| Repeater CLI / room login | 🚧 |

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
- **RX Characteristic** (`6E400002-...`): App → Radio (write with response; the ESP32 firmware does not accept write-without-response)
- **TX Characteristic** (`6E400003-...`): Radio → App (notify)

Rules the client relies on (taken from `examples/companion_radio/MyMesh.cpp`):

- Each BLE write / notification is exactly **one** complete frame. There is no stream framing over BLE, so the client never splits or re-assembles frames. An MTU of 185 is requested because frames are up to 176 bytes.
- The characteristics require an encrypted, bonded link (MITM). The watch bonds first; the PIN from *Settings → Radio BLE PIN* (default `123456`) is supplied automatically.
- Every command gets exactly one response frame. Commands are sent one at a time and wait for their response (5 s timeout), so OK/ERR can be attributed and the radio's small receive queue never overflows. Push frames (`0x80+`) and the contact stream are handled separately.
- Session start: `CMD_DEVICE_QUERY(ver 3)` → `CMD_APP_START` → set clock → battery → contacts → channels → drain message queue.

## Requirements

- **Wear OS 3.0+** (API level 30+)
- A MeshCore companion radio device flashed with the **BLE companion firmware**
- Android Studio Ladybug or newer for building

## Building

Requires JDK 17 and the Android SDK (platform 35, build-tools 35.0.0).

```bash
# Clone the repository
git clone https://github.com/thegroovex/meshcore-wearos.git
cd meshcore-wearos

# Unit tests (protocol encoder / frame parser against the firmware wire format)
./gradlew testDebugUnitTest

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
