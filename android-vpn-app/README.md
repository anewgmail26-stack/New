# Android VPN App

## What this app is

This is a real Android Studio project for a Kotlin-based starter Android VPN app. It uses Android's `VpnService` permission flow, stores a pasted V2Ray/Xray JSON configuration locally, starts a foreground service notification while the VPN service is active, and creates an Android VPN TUN interface. Real V2Ray/Xray native core integration is still a TODO before this can function as a production proxy VPN.

The project is intentionally isolated in the `android-vpn-app` folder so it does not replace or delete any existing app in the repository.

## Current limitations

- The app does **not** include V2Ray or Xray native core binaries.
- `V2RayManager` validates JSON and contains safe placeholder start/stop logic.
- The VPN service creates a TUN interface, but traffic will not be proxied to a V2Ray/Xray core until native core integration is added.
- Production VPN apps need robust routing, DNS handling, lifecycle recovery, error reporting, split tunneling rules, battery optimization handling, and security hardening.

## How to open in Android Studio

1. Install the latest stable Android Studio.
2. Open Android Studio.
3. Select **File > Open**.
4. Choose the `android-vpn-app` directory.
5. Allow Android Studio to sync Gradle and download the Android Gradle Plugin/Kotlin plugin dependencies.
6. If prompted, install the required Android SDK platform for `compileSdk 35`.

## How to add V2Ray/Xray native core binaries

1. Build or obtain trusted V2Ray/Xray core binaries for the Android ABIs you plan to support, such as:
   - `arm64-v8a`
   - `armeabi-v7a`
   - `x86_64`
2. Add the binaries under an app-private delivery path, commonly one of these approaches:
   - `app/src/main/jniLibs/<abi>/` for packaged native libraries, or
   - `app/src/main/assets/<abi>/` for executable assets copied to app-private storage at runtime.
3. Update `V2RayManager` to:
   - write the pasted JSON config to an app-private file,
   - copy/extract the matching ABI binary if needed,
   - mark the binary executable when appropriate,
   - start the core process with the generated config path,
   - monitor process health and logs,
   - stop the process when the VPN disconnects.
4. Connect `MyVpnService`'s TUN file descriptor to the core's expected Android TUN, SOCKS, or dokodemo-door integration path.
5. Review V2Ray/Xray licensing and distribution requirements before shipping binaries.

## How to build APK in Android Studio

1. Open `android-vpn-app` in Android Studio.
2. Wait for Gradle sync to finish successfully.
3. Select a build variant, usually **debug**.
4. Build a debug APK from **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. For a release APK, configure signing in Android Studio, then use **Build > Generate Signed Bundle / APK**.

From a local machine with Android SDK installed, you can also run Gradle from the `android-vpn-app` folder. If a Gradle wrapper is added later, prefer `./gradlew`; otherwise use a locally installed Gradle version compatible with the Android Gradle Plugin:

```bash
gradle assembleDebug
```

This Codex environment may not include Android SDK tooling. If `ANDROID_HOME` is not available, do not expect APK builds to run here; build locally in Android Studio or use the GitHub Actions workflow instead.

## How to download the debug APK from GitHub Actions

The repository includes a GitHub Actions workflow at `.github/workflows/android-build.yml` that builds the debug APK on a GitHub-hosted Ubuntu runner with JDK 17, Android SDK packages, and Gradle. To get the generated APK without building locally in Android Studio:

1. Merge this PR into `main`.
2. Go to the GitHub repository in your browser.
3. Open the **Actions** tab.
4. Open the latest **Android VPN App Build** workflow run.
5. Download the `android-vpn-app-debug-apk` artifact from the workflow run page.
6. Install the APK on Android after allowing installation from unknown sources for the app you use to open the APK, such as your browser or file manager.

Remember: this APK is still a starter VPN app. It can exercise the Android `VpnService` flow and create a placeholder VPN interface, but real V2Ray/Xray native core integration is still TODO.

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
