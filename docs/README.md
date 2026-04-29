# DiLink-Auto

Use your phone apps on your car's built-in screen. Open-source, no Google Services required.

An open-source alternative to Android Auto for **any Android 10+ phone** paired with **BYD DiLink 3.0+** infotainment systems. Originally motivated by the Xiaomi HyperOS / Chinese ROM gap, but works universally.

[![Sponsor](https://img.shields.io/badge/Sponsor-%E2%9D%A4-pink?logo=github)](https://github.com/sponsors/andersonlucasg3)
[![Pix](https://img.shields.io/badge/Pix-Brazil-00C2A0)](https://nubank.com.br/cobrar/5gf35/69ed4939-b2c0-4071-b75d-3b430ab70a5d)

## What It Does

DiLink-Auto mirrors your phone apps onto your car's display with full touch interaction. Launch navigation, music, messaging — any app on your phone — directly from the car screen. Notifications appear on the car's nav bar with progress indicators. H.264 video at up to 60fps, 8Mbps CBR, with the phone's screen turned off to save battery.

**Original motivation:** bridging the gap when your phone can't run Android Auto (Chinese ROM, no Google Play Services) but your car only supports Android Auto (no CarWith, CarPlay, or Carlife). But DiLink-Auto works with any Android phone — Google Services or not.

| Device | Issue |
|--------|-------|
| Xiaomi 17 Pro Max (HyperOS 3, Chinese ROM) | No Android Auto — no Google Play Services |
| BYD Destroyer 05 / King (Brazil market) | Only Android Auto on head unit |
| Any Android 10+ phone | Works regardless of ROM or Play Services |

## Requirements

**Phone:**
- Any Android 10+ phone
- USB Debugging enabled (Developer Options)
- All Files Access permission (prompted on first launch)

**Car:**
- BYD DiLink 3.0 or newer
- One free USB-A port

**Phone hotspot must be enabled** — the car connects to your phone's WiFi hotspot. No pairing codes, no Google account needed.

## How It Works

1. **Enable hotspot** — Turn on your phone's WiFi hotspot. The car connects to it.
2. **Plug in** — Connect your phone to the car's USB port
3. **Auto-install** — The phone installs the car app via WiFi ADB (first time only, one tap)
4. **Auto-connect** — 3 dedicated WiFi TCP streams: video (port 9638), touch input (port 9639), and control (port 9637)
5. **Use your apps** — Launch any app from the car's launcher screen. It runs on the phone, appears on the car, and responds to touch

The phone runs your apps on a virtual display, encodes the screen as H.264 video, and streams it to the car. Touches on the car screen are sent back to the phone and injected as real touch events. The phone's physical screen stays off (battery saving) and can be used independently.

## Install

<a href="https://github.com/andersonlucasg3/dilink-auto-android/releases/latest"><img src="https://img.shields.io/github/v/release/andersonlucasg3/dilink-auto-android?label=Download%20Latest%20Release" alt="Download Latest Release"></a>

Download the latest release or build from source:

1. **Build:** `./gradlew :app-client:assembleDebug`
2. **Install** the APK at `app-client/build/outputs/apk/debug/app-client-debug.apk` on your phone only
3. **Enable USB Debugging** on your phone (Settings → Developer Options)
4. **Open DiLink-Auto** on the phone and grant All Files Access when prompted
5. **Enable hotspot, then plug into car USB** — the car app auto-installs on first run over WiFi ADB

The car APK and VD server JAR are bundled inside the phone APK — you never install anything on the car yourself.

## Current Status

**Working:**
- 60fps H.264 video streaming (8Mbps CBR, Main profile, configurable via handshake)
- Full touch input (multi-touch, pinch-to-zoom)
- App launcher with search, alphabetical sort, 64dp icons
- Notifications on car screen with progress bars, tap to open
- Self-update via GitHub Releases (release) or prereleases (debug)
- Auto-update: phone detects outdated car app and updates it over WiFi ADB
- Phone screen off during streaming (battery saving)
- Guided onboarding for all required permissions
- Internationalization: English, Portuguese, Russian, Belarusian, French, Kazakh, Ukrainian, Uzbek
- Display restore after USB disconnect (v0.14.0+)
- Tested on BYD DiLink 3.0 (1920x990) + Xiaomi 17 Pro Max (Android 16) + POCO F5

**Coming:** audio streaming, media controls, navigation widgets

**Known limitations:**
- VD server process restarts on USB disconnect (reconnects automatically).
- Hotspot must be enabled manually (Android 16 limitation).
- Occasional visual artifacts — decoder restart race, recovers at next keyframe (~1s).
- Streaming latency ~100-200ms under load. CBR 8Mbps.
- Display may stay off after abrupt USB disconnect (fixed in v0.14.0).

## Documentation

| Document | Audience | Description |
|----------|----------|-------------|
| [Setup Guide](./setup.md) | Users | Detailed install and troubleshooting |
| [Architecture](./architecture.md) | Developers | Module design, connection flow, design decisions |
| [Protocol Specification](./protocol.md) | Developers | Wire format, message types, port assignment |
| [Client (Phone) App](./client.md) | Developers | ConnectionService, VD JAR deploy, auto-update |
| [Server (Car) App](./server.md) | Developers | State machine, USB ADB, VideoDecoder, car UI |
| [Progress Tracker](./progress.md) | Contributors | Feature status, milestones, roadmap |

## Project Structure

The phone APK (`app-client`) embeds both the car APK (`app-server`) and the VD server JAR. When you install the phone app, everything needed is bundled inside.

```
DiLink-Auto/
├── protocol/       Shared library (framing, messages, discovery, USB ADB)
├── app-client/     Phone APK — relay, VD deploy, car auto-update, FileLog
├── app-server/     Car APK — UI, connection state machine, video decoder
├── vd-server/      VirtualDisplay server (compiled to JAR, deployed by phone)
├── docs/           Documentation
└── gradle/         Build system
```

## Support

This project is developed independently and relies on community support. Every contribution helps cover development time, testing devices, and keeping the project alive.

## Contributing

PRs welcome. See [Architecture](./architecture.md) and [Protocol](./protocol.md) for technical context. Build with `./gradlew :app-client:assembleDebug` (JDK 17+, Android SDK 34).

### Branching Model (Git-Flow + Issue Types)

Branches are created automatically by the issue agent based on the **issue template** used:

| Template | Label | Branch Pattern | Purpose |
|----------|-------|---------------|---------|
| Bug Fix | `bug` | `fix/N-agent` | Bug fixes |
| New Feature | `feature` | `feature/N-agent` | New features |
| Investigation | `investigation` | `investigate/N-agent` | Codebase investigation |
| Documentation | `documentation` | `docs/N-agent` | Documentation updates |
| Release | `release` | `release/vX.Y.Z` | Release preparation |
| Agent Task (generic) | — | `issue/N-agent` | Catch-all |

All branches merge to `develop` via PR, except `release/*` which targets `main`.

### CI Workflows

| Workflow | Trigger | Action |
|----------|---------|--------|
| `build.yml` | Push/PR to `main` | Validation: build release APK |
| `build-develop.yml` | Push/PR to `develop`, `release/*` | Validation: build debug APK |
| `build-pre-release.yml` | Tag `vX.Y.Z-dev-NN` | Build debug APK + GitHub pre-release |
| `build-release.yml` | Tag `vX.Y.Z` | Build signed release APK + GitHub Release |
| `sync-main-to-develop.yml` | Push to `main` | Merge `main` → `develop` (git-flow back-sync) |
| `issue-agent.yml` | Issue opened / comment | Autonomous agent: branch, build, PR |

All CI runs on **self-hosted WSL runners**.

**Release process:** Create a Release issue from the template. The agent creates `release/vX.Y.Z`, prepares changes, and tags `vX.Y.Z-dev-NN`. The `build-pre-release.yml` workflow builds and publishes a pre-release for testing. When ready, `release/vX.Y.Z` is merged to `main`. Pushing the `vX.Y.Z` tag triggers `build-release.yml`, which builds a signed APK and creates the GitHub Release. The `sync-main-to-develop.yml` workflow automatically merges `main` back into `develop`, ensuring any last-minute release changes flow back.

**Pre-release updates:** Users on the Pre-release channel receive `-dev` builds. Users on the Release channel receive stable builds only. The channel is configurable in Settings.

## License

MIT — see [LICENSE](../LICENSE)
