# My Tunnel Lite Android VPN

## What this app is

My Tunnel Lite is a Kotlin Android tunnel VPN style starter app. It keeps the existing Android `VpnService` flow and foreground notification, then adds a green/white tunnel UI, built-in profile choices, sample server and payload/tweak models, DNS preference storage, and generated Xray-compatible config storage.

The app is still a starter integration project. It can request VPN permission, show a foreground notification, generate Xray-compatible JSON from built-in selections, save that generated config into app-private storage, and create an Android VPN TUN interface. The repository now contains arm64 native test libraries, but the app does **not** proxy real user traffic through Xray/V2Ray until a documented Java/Kotlin wrapper, AAR, or source API is wired to start and stop the native runtime.

## Current limitations

- The app includes these arm64 native test files under `app/src/main/jniLibs/arm64-v8a/`: `libxray.so`, `libtun2socks.so`, and `libgojni.so`.
- `CoreBridge` and `TunnelCoreManager` detect the packaged Xray core, tun2socks, and gojni files, generate app-private Xray JSON, and expose safe start/stop seams for a real native runtime.
- START remains blocked with `Native core files present, start API not wired` because no documented Java/Kotlin JNI wrapper, AAR, source API, or executable start adapter is bundled.
- `MyVpnService` can create an Android TUN interface only after the preflight checks pass, but traffic forwarding is not connected to a native core yet because the real start API is still unknown.
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

Remember: this APK detects the added arm64 native core files and reports `Native core files present, start API not wired`. It does not provide real Xray/V2Ray traffic proxying until a documented Java/Kotlin wrapper, AAR, source API, or executable native start adapter is implemented.

## How to add real Xray/V2Ray native core support

`CoreBridge` is real-ready but intentionally conservative: it detects packaged native libraries and writes the generated Xray JSON, then refuses to claim a running tunnel until a real native start path exists. A `.so` file is a shared library, not a command-line executable; Android loads it from `jniLibs` into `applicationInfo.nativeLibraryDir`. To run traffic you must provide one of these real integration paths:

1. **JNI/AAR wrapper path:** add a trusted Xray/V2Ray Android AAR or JNI wrapper that exposes documented start/stop APIs. Wire those APIs into `CoreBridge.NativeRuntimeAdapter`.
2. **Executable path:** ship a trusted executable core binary and tun2socks executable using a compliant packaging/extraction approach, mark the copied executable file executable in app-private storage, then start it with the generated config path. Do not try to launch `libxray.so` directly with `ProcessBuilder`.

Expected packaged library locations detected by the app are:

- `app/src/main/jniLibs/arm64-v8a/libxray.so` (present in this repository)
- `app/src/main/jniLibs/arm64-v8a/libv2ray.so`
- `app/src/main/jniLibs/arm64-v8a/libtun2socks.so` (present in this repository)
- `app/src/main/jniLibs/arm64-v8a/libgojni.so` (present in this repository)
- `app/src/main/jniLibs/armeabi-v7a/libxray.so`
- `app/src/main/jniLibs/armeabi-v7a/libv2ray.so`
- `app/src/main/jniLibs/armeabi-v7a/libtun2socks.so`
- `app/src/main/jniLibs/armeabi-v7a/libgojni.so`

The app requires one Xray/V2Ray core (`libxray.so` or `libv2ray.so`), one `libtun2socks.so`, and one `libgojni.so` for the device ABI before it reports native core files as present. The prepared ABI folders are `arm64-v8a` and `armeabi-v7a`; add more ABI folders only when the Gradle build and bridge detection are updated to support them. Detecting these `.so` files is not enough to run traffic: a documented Java/Kotlin wrapper, AAR, or source API is still needed so `CoreBridge.NativeRuntimeAdapter` can call known start/stop methods without inventing JNI names.

After adding real native artifacts, APK size will increase because native cores and tun2socks include compiled machine code for each ABI. Shipping both `arm64-v8a` and `armeabi-v7a` means the APK carries separate binaries for 64-bit and 32-bit ARM devices.

Without a real Xray/V2Ray core, TUN routing through tun2socks, gojni/wrapper support, and a documented start API, the app remains non-functional for real traffic. It may create an Android VPN interface and foreground notification only after preflight succeeds, but packets will not be proxied safely to the selected server until the native runtime adapter is implemented.

Before distributing binaries, review licenses for Xray-core, V2Ray-core, tun2socks, Go runtime dependencies, and any wrapper/AAR you include. Keep source notices and comply with redistribution terms. Do not commit placeholder or fake native binaries.

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
