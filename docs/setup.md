# Setup Guide

## Prerequisites

- **Phone:** Any Android 10+ device with USB Debugging enabled
- **Car:** BYD DiLink 3.0+ (or any Android 10+ head unit with USB host port)
- **USB cable:** Phone to car USB port
- **Development:** Android Studio or Gradle, JDK 17

**No internet connection is required** — DiLink-Auto streams over your phone's WiFi hotspot (the car connects directly to the phone). An internet connection is only needed for the apps running on your phone (e.g., maps, music), not for DiLink-Auto itself.

## Phone Setup (One-Time)

1. **Install DiLink Auto Client**: `adb install app-client-debug.apk`
2. **Open the app** — the onboarding screen will guide you through each permission:
   - **All Files Access** — deploys the virtual display server to storage
   - **Battery Optimization** — keeps streaming alive when screen is off
   - **Accessibility Service** — enables car touchscreen control
   - **Notification Access** — forwards phone notifications to car display
3. Each step opens the relevant system settings. Grant the permission, then press Back to continue.
4. You can skip any step and configure it later from the main screen.

That's it. No Wireless Debugging, no pairing codes, no special WiFi configuration.

### Optional: Shizuku (ADB-Free Connection)

[Shizuku](https://github.com/RikkaApps/Shizuku) provides elevated shell privileges to normal apps, allowing DiLink-Auto to deploy the Virtual Display server without needing a USB ADB connection from the car. This is an alternative to the USB ADB track — the car only needs WiFi connectivity.

1. **Install Shizuku** from [GitHub Releases](https://github.com/RikkaApps/Shizuku/releases) or Google Play
2. **Start Shizuku** once via ADB (`adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh`) or via root
3. **Open DiLink Auto** → Settings → Shizuku → tap to grant permission
4. When Shizuku is active, the phone deploys the VD server directly. No need for USB ADB or Wireless Debugging.

With Shizuku active, the car only needs to connect over WiFi — the USB cable is not required for streaming.

## Car Setup

**No internet connection required.** The car APK is embedded inside the phone APK — the phone pushes it to the car over local WiFi. No internet connection is needed at any point during setup or streaming.

No manual car installation needed. On first connection (or version mismatch), the phone sends `UPDATING_CAR` to the car (which shows "Updating..." status), then auto-installs via dadb (WiFi ADB).

Alternatively, use the "Install on Car" button in the phone app to manually push the car APK.

If installing manually: `adb install app-server-debug.apk` (requires ADB access to the car).

## Daily Use

1. **Plug phone into car USB** — car app auto-launches (or connect via WiFi on same network)
2. Car and phone connect automatically via parallel WiFi + USB tracks
3. Phone deploys VD server and streaming begins at 60fps
4. Use the car touchscreen to interact with phone apps

## Building

```bash
# Build phone APK (triggers server build + embed automatically)
./gradlew :app-client:assembleDebug

# APK location:
# app-client/build/outputs/apk/debug/app-client-debug.apk  (phone -- includes embedded car APK)
```

The build system automatically builds app-server and embeds it into app-client, so only one APK needs to be installed manually.

## How It Works

When the phone is connected to the car:

1. **Car starts parallel tracks** — WiFi discovery and USB detection run simultaneously
2. **Track A (WiFi):** gateway IP + mDNS discovery → NIO connect to phone control port (9637)
3. **Track B (USB):** scan devices → USB ADB connect → launch phone app via `am start`
4. **Handshake:** car sends viewport + DPI + appVersionCode + targetFps; phone sends device info + vdServerJarPath
5. **Version check:** phone compares appVersionCode — if mismatch, sends UPDATING_CAR message to car, then auto-updates via dadb
6. **3-connection setup:** car opens video (9638) + input (9639) connections after handshake
7. **Phone deploys VD server** — extracts vd-server.jar to `/sdcard/DiLinkAuto/`, starts `app_process` as shell UID with FPS arg
8. **VD server reverse-connects** to phone on localhost:19637 (NIO non-blocking)
9. **VD server creates VirtualDisplay** at phone's native DPI (480dpi) with GPU downscale and periodic re-draw
10. **Video streams** over WiFi TCP (video connection 9638) — H.264, Main profile, 8Mbps CBR, configurable up to 60fps

## Troubleshooting

### ADB auth dialog appears every time (FIXED in v0.13.1)
Fixed — the issue was double-hashing the AUTH_TOKEN. ADB sends a raw 20-byte token that must be treated as a pre-hashed SHA-1 digest. The old code used `SHA1withRSA` which hashed it again. Now uses `NONEwithRSA` with SHA-1 DigestInfo prefix (prehashed), matching AOSP's `RSA_sign(NID_sha1)`. The phone accepts AUTH_SIGNATURE on reconnect and "Always allow" persists correctly.

If the dialog still appears on the first connection after updating, check "Always allow" — it should not appear again on subsequent connections. If it persists, check the phone's Developer Options for "Disable ADB authorization timeout" (Android 11+).

### Phone app does not launch
- Ensure USB Debugging is enabled on the phone
- Check that the car's USB port supports host mode (not all ports do)
- Try a different USB cable (some cables are charge-only)

### All Files Access permission denied
- The phone app needs MANAGE_EXTERNAL_STORAGE to deploy vd-server.jar to `/sdcard/DiLinkAuto/`
- Go to Settings -> Apps -> DiLink Auto -> Permissions -> All Files Access -> ON

### Video not streaming / black screen
- Ensure phone and car are on the same network
- Check that both apps are running (phone shows "Streaming", car shows video)
- The VD server may need a moment to start — wait 5-10 seconds after connecting
- The SurfaceScaler periodic re-draw should produce frames even on static content
- Check `/sdcard/DiLinkAuto/client.log` for diagnostic information

### Connection drops
- Previously caused by unrelated network drops (mobile data cycling) triggering proactive disconnect
- Fixed in v0.12.5: smart network callback ignores drops on networks that don't carry the connection
- If persistent, check `/sdcard/DiLinkAuto/client.log` for "Network lost" entries

### Car app not updating
- The phone auto-updates the car app when a version mismatch is detected during handshake
- Car shows "Updating car app..." status during the update
- You can also manually trigger an update with the "Install on Car" button in the phone app
- Ensure dadb can reach the car over WiFi ADB

### Logs
- Phone logs: `/sdcard/DiLinkAuto/client.log` (current session)
- Previous sessions: `/sdcard/DiLinkAuto/client-YYYYMMDD-HHmmss.log`
- VD server logs: `/data/local/tmp/vd-server.log` (on phone, readable via ADB)
- Car logs: routed to phone's client.log via protocol DATA channel (tag: `CarLog`)
- Pull logs: `adb shell "cat /sdcard/DiLinkAuto/client.log"`

## HyperOS (Xiaomi) Tips

For reliable operation on HyperOS:
1. Settings -> Apps -> DiLink Auto -> Autostart -> Enable
2. Settings -> Battery -> DiLink Auto -> No Restrictions
3. Lock the app in Recent Apps (long-press the card -> Lock)

## Samsung One UI Tips

Samsung devices running One UI 5+ (Android 13+) have additional security and power-saving features that can block DiLink-Auto from working correctly. This applies to Galaxy A, M, S, Z, and Tab series.

### Disable Auto Blocker (Critical for USB ADB)

**Auto Blocker** blocks USB commands and can prevent the car from connecting to your phone via USB ADB. This is the most common Samsung-specific issue.

1. **Settings → Security and privacy → Auto Blocker → Off**
2. If you prefer to keep Auto Blocker on, at minimum disable the **"Block commands over USB cable"** option

### Allow All Files Access

Samsung's Permission Manager may auto-revoke permissions from apps you haven't opened recently:

1. **Settings → Apps → DiLink Auto → Permissions → Files and media → Allow management of all files**
2. Toggle **"Allow management of all files"** ON
3. Verify it stays ON after closing settings (Samsung may show a confirmation popup)

### Disable Battery Optimization

Samsung's battery management is more aggressive than stock Android:

1. **Settings → Apps → DiLink Auto → Battery → Unrestricted**
2. **Settings → Battery → Background usage limits → Never sleeping apps → Add DiLink Auto**
3. **Settings → Battery → Background usage limits → Deep sleeping apps → Remove DiLink Auto** if listed

### Lock App in Recents

Samsung One UI may kill background apps to free memory:

1. Open Recent Apps (swipe up from bottom with 3-button nav, or gesture nav)
2. Tap the DiLink Auto icon at the top of its card
3. Select **"Keep open"**

### Disable Samsung Device Care Auto-Optimization

Samsung's Device Care can automatically stop background services:

1. **Settings → Battery and device care → Automation → Auto optimize daily → Off**
2. **Settings → Battery and device care → Automation → Auto restart → Off**

### If You See a "DeX" Permission Popup

Some Samsung devices show a popup about "Samsung DeX" or "external display" permissions when an app tries to create a virtual display. Even though Galaxy A/M series don't support DeX, the dialog may still appear. Simply tap **"Allow"** or **"Start now"**. If the dialog keeps reappearing, go to **Settings → Connected devices → Samsung DeX** and disable "Auto start when HDMI is connected."

### Knox Security Considerations

Samsung Knox may show a security notification when DiLink-Auto accesses:
- Virtual display surface (for video encoding)
- USB debugging bridge (for car ADB connection)
- All files storage (for deploying the VD server)

These are expected behaviors. Tap "Allow" or "OK" on any Knox-related prompts. If prompts persist, you can temporarily lower Knox protection to "Medium" under **Settings → Security and privacy → Samsung Knox**.
