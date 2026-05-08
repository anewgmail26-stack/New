# Native tunnel binaries (armeabi-v7a)

Place trusted 32-bit Android native libraries here only if you need armeabi-v7a device support.

Expected files match the arm64 runtime contract:

- `libgojni.so` - gomobile JNI bridge that contains/loads the libv2ray runtime APIs used by the app.
- `libtun2socks.so` - TUN-to-SOCKS routing binary/library used after Android creates the VPN interface.

Do not add stale standalone `libxray.so` or `libv2ray.so` files here. The app no longer detects or requires those separate core files because selecting an incompatible standalone core can break START. If you rebuild a runtime that really needs extra core libraries, update `CoreBridge`, the gomobile Java wrappers, and this documentation in the same change.
