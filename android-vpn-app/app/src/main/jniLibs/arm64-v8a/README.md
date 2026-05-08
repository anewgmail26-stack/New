# Native tunnel binaries (arm64-v8a)

This ABI folder is intentionally kept small and only contains the runtime pieces that `CoreBridge` actually starts:

- `libgojni.so` - gomobile JNI bridge that contains/loads the libv2ray runtime APIs used by the app.
- `libtun2socks.so` - TUN-to-SOCKS routing binary/library used after Android creates the VPN interface.

Do not add stale standalone `libxray.so` or `libv2ray.so` files here. The app no longer detects or requires those separate core files because selecting an incompatible standalone core can break START. If you rebuild a runtime that really needs extra core libraries, update `CoreBridge`, the gomobile Java wrappers, and this documentation in the same change.

Only commit real, licensed, release-built binaries from a verified build pipeline.
