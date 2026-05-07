# My Tunnel Lite Android VPN

My Tunnel Lite is a Kotlin Android VLESS/V2Ray VPN client. The app now packages the native Xray/libv2ray runtime (`libgojni.so`/`libxray.so`) and `libtun2socks.so`, generates an Xray-compatible VLESS configuration from the selected server, starts libv2ray through Java/Kotlin gomobile bindings, creates an Android `VpnService` TUN interface, and routes that TUN traffic to the local SOCKS inbound with tun2socks.

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
3. `CoreBridge` loads the gomobile `libgojni` binding, initializes libv2ray, validates the generated config, and starts `V2RayPoint.runLoop()`.
4. `MyVpnService` creates an Android TUN interface and passes socket protection callbacks to the core.
5. `CoreBridge` starts `libtun2socks.so` with the VPN file descriptor and forwards traffic to the local SOCKS inbound.

## Build APK locally

From a machine with Android SDK and JDK 17 installed:

```bash
cd android-vpn-app
gradle assembleDebug
```

If your shell defaults to another JDK, set `JAVA_HOME` to JDK 17 first.

## Notes

- This project currently includes arm64 native libraries in `app/src/main/jniLibs/arm64-v8a/`. Add matching libraries under `armeabi-v7a` if you need 32-bit devices.
- The native libraries are extracted at install time so `libtun2socks.so` can be executed by the app process.
- Production deployments should use real server values, secure distribution, logging/crash handling, and device testing across Android versions.
