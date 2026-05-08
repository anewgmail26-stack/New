# My Tunnel Lite Android VPN

My Tunnel Lite is a Kotlin Android VLESS/V2Ray VPN client. The app packages the gomobile V2Ray runtime (`libgojni.so`) and `libtun2socks.so`, generates an Xray-compatible VLESS configuration from the selected server, starts libv2ray through Java/Kotlin gomobile bindings, creates an Android `VpnService` TUN interface, and routes that TUN traffic to the local SOCKS inbound with tun2socks. Standalone `libxray.so`/`libv2ray.so` files are intentionally removed and are not required by the app runtime.

## Update your server

Edit `app/src/main/assets/servers.json` before building/releasing the APK. Keep the JSON array shape and replace the placeholder host, UUID, SNI, WebSocket path, and other fields with your own server values.

Supported server fields:

- `id`, `name`, `remark`
- `host`, `port`, `uuid`
- `type`: `tcp` or `ws`
- `security`: `tls` or `none`
- `sni`, `hostHeader`, `wsPath`
- `encryption` (usually `none` for VLESS)
- `allowInsecure`
- `flow` (for example leave blank unless your server requires an XTLS/REALITY flow supported by the bundled core)

After you update `servers.json`, build and install the app. The server selector uses this asset automatically.

## Runtime flow

1. The UI saves the selected VLESS server/payload/DNS preference.
2. `TunnelProfile.toXrayJson()` writes `xray-generated-config.json` in app-private storage with a SOCKS inbound at `127.0.0.1:10808`.
3. `CoreBridge` loads the gomobile `libgojni` runtime, initializes libv2ray, validates the generated config, and starts `V2RayPoint.runLoop()`.
4. `MyVpnService` creates an Android TUN interface and passes socket protection callbacks to the runtime.
5. `CoreBridge` starts `libtun2socks.so` with the VPN file descriptor and forwards traffic to the local SOCKS inbound.

## Build APK locally

From a machine with Android SDK and JDK 17 installed:

```bash
cd android-vpn-app
gradle assembleDebug
```

If your shell defaults to another JDK, set `JAVA_HOME` to JDK 17 first.

## Notes

- This project currently includes arm64 native libraries in `app/src/main/jniLibs/arm64-v8a/`: `libgojni.so` for the V2Ray runtime and `libtun2socks.so` for TUN routing. Add matching libraries under `armeabi-v7a` if you need 32-bit devices.
- Do not add stale standalone `libxray.so`/`libv2ray.so` files unless the Java/Kotlin gomobile binding is rebuilt to use them; the app detects only `libgojni.so` plus `libtun2socks.so`.
- The native libraries are extracted at install time so `libtun2socks.so` can be executed by the app process.
- Production deployments should use real server values, secure distribution, logging/crash handling, and device testing across Android versions.

## Bundled VLESS server

`app/src/main/assets/servers.json` includes the free `vpn` profile first in the selector:

- Address: `shar1.knlvpn.com:80`
- UUID: `48990253-ed95-4aac-9ad3-ad4457e50b14`
- Protocol: `vless`
- Transport: WebSocket (`type`/`network`: `ws`)
- Security: `none`
- Encryption: `none`
- WebSocket path: `/`
- WebSocket Host header: `telegram.org`
- SNI: empty
- `allowInsecure`: `false`
- `enabled`: `true`
- `premiumLabel`: `free`
- `sortOrder`: `1`

The generated Xray config keeps the outbound protocol as `vless`, writes `streamSettings.network` as `ws`, writes `streamSettings.security` as `none`, and emits `wsSettings.path` plus `wsSettings.headers.Host` from the selected server.

## START crash troubleshooting

START should not terminate the app process if the native runtime fails to load or start. `MyVpnService` and `CoreBridge` now catch and log native-start failures such as `UnsatisfiedLinkError`, `NoClassDefFoundError`, `IllegalStateException`, `IOException`, and other unexpected `Throwable`s. On failure the app releases the VPN interface, stops the foreground VPN state, broadcasts a disconnected status, and shows `Start failed: <reason>` instead of claiming that the VPN is connected.

Useful logcat tags while debugging START are:

- `VpnProfile` for the selected server/profile.
- `XrayConfig` for the generated config path.
- `NativeRuntime` for native library/API detection.
- `V2RayStart` for libv2ray start results.
- `Tun2SocksStart` for tun2socks start results.

If one of these logs says a native library or runtime API is missing/incomplete, rebuild the APK with compatible `libgojni.so` and `libtun2socks.so` for the device ABI, then test START again on a physical device or emulator.
