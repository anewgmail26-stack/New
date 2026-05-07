# My Tunnel Lite Android VPN

## What this app is

My Tunnel Lite is a Kotlin Android tunnel VPN style starter app. It keeps the existing Android `VpnService` flow and foreground notification, then adds a green/white tunnel UI, built-in profile choices, sample server and payload/tweak models, DNS preference storage, and generated Xray-compatible config storage.

The app is still a starter integration project. It can request VPN permission, show a foreground notification, generate Xray-compatible JSON from built-in selections, save that generated config into app-private storage, and create an Android VPN TUN interface. It does **not** proxy real user traffic through Xray/V2Ray until native core binaries and TUN/tun2socks wiring are added.

## Current limitations

- The app does **not** include Xray, V2Ray, tun2socks, or other native proxy/VPN core binaries.
- `TunnelCoreManager` checks for native core files, generates app-private Xray JSON, and keeps start/stop methods ready for real native execution.
- `MyVpnService` creates an Android TUN interface, but traffic forwarding is not connected to a native core yet.
- Upload/download counters are UI placeholders until real traffic accounting is connected.
- The connected/disconnected UI reflects the app button flow and is not a full service-state observer yet.
- Production VPN apps still need robust routing, DNS handling, lifecycle recovery, split tunneling rules, battery optimization handling, logging, crash recovery, and security hardening.

## UI and local config features

- Green/white tunnel style interface named **My Tunnel Lite**.
- Connection status, upload/download counters, and VPN duration timer.
- Server selector card backed by built-in sample server models.
- Payload/Tweak selector card backed by sample payload models.
- DNS checkbox saved locally.
- Large circular START/STOP button.
- Bottom navigation placeholders for Updates, Telegram, Tools, and Exit.
- Tools dialog with **Core Status**, **Check Updates**, and **About My Tunnel Lite**.

## Generated Xray config format

The app now works only from the built-in server selector, payload/tweak selector, and DNS checkbox. It generates Xray-compatible JSON from those selections and saves the generated config internally; there is no user-facing pasted-config workflow or JSON text editor.

Sample generated JSON shape:

```json
{
  "log": {
    "loglevel": "warning"
  },
  "dns": {
    "servers": [
      "localhost"
    ]
  },
  "inbounds": [
    {
      "tag": "tun-in",
      "listen": "127.0.0.1",
      "port": 10808,
      "protocol": "socks",
      "settings": {
        "udp": true
      }
    }
  ],
  "outbounds": [
    {
      "tag": "proxy",
      "protocol": "vless",
      "settings": {
        "vnext": [
          {
            "address": "edge.example.net",
            "port": 443,
            "users": [
              {
                "id": "00000000-0000-4000-8000-000000000001",
                "encryption": "none",
                "flow": ""
              }
            ]
          }
        ]
      },
      "streamSettings": {
        "network": "tcp",
        "security": "tls",
        "tlsSettings": {
          "serverName": "edge.example.net",
          "allowInsecure": false
        }
      }
    },
    {
      "tag": "direct",
      "protocol": "freedom"
    }
  ],
  "routing": {
    "domainStrategy": "AsIs",
    "rules": []
  },
  "myTunnelLite": {
    "profileName": "Sample Green Edge",
    "payloadTweak": {
      "id": "http-default",
      "name": "Default HTTP Tweak",
      "mode": "HTTP",
      "payload": "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf][crlf]"
    },
    "dnsEnabled": false
  }
}
```

## How to open in Android Studio

1. Install the latest stable Android Studio.
2. Open Android Studio.
3. Select **File > Open**.
4. Choose the `android-vpn-app` directory.
5. Allow Android Studio to sync Gradle and download the Android Gradle Plugin/Kotlin plugin dependencies.
6. If prompted, install the required Android SDK platform for `compileSdk 35`.

## How to build APK locally

From a local machine with Android SDK installed, run Gradle from the `android-vpn-app` folder. If a Gradle wrapper is added later, prefer `./gradlew`; otherwise use a locally installed Gradle version compatible with the Android Gradle Plugin:

```bash
gradle assembleDebug
```

This Codex environment may not include Android SDK tooling. If `ANDROID_HOME` is unavailable, build locally in Android Studio or use the GitHub Actions workflow instead.

## How to build and download APK from GitHub Actions

The repository includes `.github/workflows/android-build.yml`, which builds the debug APK on a GitHub-hosted Ubuntu runner with JDK 17, Android SDK packages, and Gradle.

1. Push a branch or open a pull request that changes files under `android-vpn-app/` or the workflow file.
2. Go to the GitHub repository in your browser.
3. Open the **Actions** tab.
4. Open the latest **Android VPN App Build** workflow run.
5. Wait for **Build debug APK** to finish successfully.
6. Download the `android-vpn-app-debug-apk` artifact from the workflow run page.
7. Install the APK on Android after allowing installation from unknown sources for the browser or file manager used to open it.

Remember: this APK prepares native-core integration paths and reports missing core files. It does not provide real Xray/V2Ray traffic proxying until real native core binaries and tun2socks routing are implemented.

## How to add real Xray/V2Ray native core binaries later

1. Build or obtain trusted Xray/V2Ray/tun2socks binaries for the Android ABIs you plan to support, such as:
   - `arm64-v8a`
   - `armeabi-v7a`
   - `x86_64`
2. Add the binaries under one of the prepared app paths:
   - `app/src/main/jniLibs/arm64-v8a/` or `app/src/main/jniLibs/armeabi-v7a/` for packaged native libraries, or
   - `app/src/main/assets/core/` for verified runtime assets copied to app-private storage at runtime.
   The code recognizes `libxray.so`, `libv2ray.so`, and `libtun2socks.so`.
3. Update `TunnelCoreManager` to:
   - write the selected/generated JSON config to an app-private file,
   - copy or extract the matching ABI binary when needed,
   - mark executable assets executable when appropriate,
   - start the native core process with the generated config path,
   - bridge the `VpnService` TUN file descriptor into Xray/V2Ray/tun2socks,
   - monitor process health and logs,
   - stop the process when the VPN disconnects.
4. Update `MyVpnService` routing and DNS behavior to match the chosen core integration approach.
5. Review Xray/V2Ray/tun2socks licensing, security, and distribution requirements before shipping binaries.

## Required Android permissions

The manifest declares:

- `android.permission.INTERNET` for network access.
- `android.permission.ACCESS_NETWORK_STATE` for network state awareness.
- `android.permission.FOREGROUND_SERVICE` for the active VPN foreground service.
- `android.permission.FOREGROUND_SERVICE_SPECIAL_USE` for the Android 14+ foreground service type used by the VPN service.
- `android.permission.POST_NOTIFICATIONS` so the foreground notification can be shown on Android 13+ after runtime approval.
- `android.permission.BIND_VPN_SERVICE` on `MyVpnService`, which is required for Android to bind the service as a VPN provider.

## Notes about `VpnService`

- Android requires explicit user approval before an app can create a VPN connection.
- `MainActivity` calls `VpnService.prepare(...)` before starting the VPN service.
- If approval is required, Android shows a system VPN consent screen.
- Once approved, `MyVpnService` can call `VpnService.Builder.establish()` to create a TUN interface.
- Only one VPN app is generally active at a time for a user profile, so connecting this app can disconnect another active VPN.
- The foreground notification must remain visible while the VPN is connected.
