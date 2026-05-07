# My Tunnel Lite Android VPN

## What this app is

My Tunnel Lite is a Kotlin Android tunnel VPN style starter app. It keeps the existing Android `VpnService` flow, foreground notification, and raw JSON config storage, then adds a green/white tunnel UI, local profile choices, sample server and payload/tweak models, DNS preference storage, and VLESS share-link import.

The app is still a starter integration project. It can request VPN permission, show a foreground notification, validate JSON, and create an Android VPN TUN interface. It does **not** proxy real user traffic through Xray/V2Ray until native core binaries and TUN/tun2socks wiring are added.

## Current limitations

- The app does **not** include Xray, V2Ray, tun2socks, or other native proxy/VPN core binaries.
- `TunnelCoreManager` validates JSON and uses safe placeholder start/stop logic only.
- `MyVpnService` creates an Android TUN interface, but traffic forwarding is not connected to a native core yet.
- Upload/download counters are UI placeholders until real traffic accounting is connected.
- The connected/disconnected UI reflects the app button flow and is not a full service-state observer yet.
- Production VPN apps still need robust routing, DNS handling, lifecycle recovery, split tunneling rules, battery optimization handling, logging, crash recovery, and security hardening.

## UI and local config features

- Green/white tunnel style interface named **My Tunnel Lite**.
- Connection status, upload/download counters, and VPN duration timer.
- Server selector card backed by sample server models plus the latest imported VLESS server.
- Payload/Tweak selector card backed by sample payload models.
- DNS checkbox saved locally.
- Large circular START/STOP button.
- Bottom navigation placeholders for Updates, Telegram, Tools, and Exit.
- Raw JSON config editor remains available for manual Xray/V2Ray-compatible JSON.

## VLESS import format

Paste a `vless://` share link into the VLESS import box and tap **Import VLESS link**.

Example:

```text
vless://00000000-0000-4000-8000-000000000001@example.com:443?type=tcp&security=tls&sni=example.com&encryption=none&allowInsecure=0#Example%20Server
```

The importer validates and parses:

- `uuid` from the user-info section before `@`
- `host`
- `port`
- `type` query parameter, defaulting to `tcp`
- `security` query parameter, defaulting to `none`
- `sni` or `serverName` query parameter
- `encryption` query parameter, defaulting to `none`
- `allowInsecure` query parameter (`1`, `true`, and `yes` mean true)
- remark/name from the URL fragment after `#`

Invalid links show clear field errors and toast messages, such as missing UUID, invalid UUID, missing host, missing valid port, or unsupported non-`vless://` input.

## Server config JSON format

The app converts the selected sample server, selected payload/tweak, DNS option, or imported VLESS link into internal JSON and saves it locally. Raw JSON can also be pasted and saved manually.

Sample generated JSON shape:

```json
{
  "app": "My Tunnel Lite",
  "profileName": "Sample Green Edge",
  "dnsEnabled": false,
  "payloadTweak": {
    "id": "http-default",
    "name": "Default HTTP Tweak",
    "mode": "HTTP",
    "payload": "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf][crlf]"
  },
  "outbounds": [
    {
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
    }
  ],
  "remarks": "Sample Green Edge"
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

Remember: this APK still uses placeholder tunnel-core logic. It does not provide real VLESS/Xray/V2Ray traffic proxying until native core integration is implemented.

## How to add real Xray/V2Ray native core binaries later

1. Build or obtain trusted Xray/V2Ray/tun2socks binaries for the Android ABIs you plan to support, such as:
   - `arm64-v8a`
   - `armeabi-v7a`
   - `x86_64`
2. Add the binaries under an app-private delivery path, commonly one of these approaches:
   - `app/src/main/jniLibs/<abi>/` for packaged native libraries, or
   - `app/src/main/assets/<abi>/` for executable assets copied to app-private storage at runtime.
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
