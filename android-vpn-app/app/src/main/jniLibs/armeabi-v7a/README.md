# Native core binaries

Place trusted, release-built Android native libraries for this ABI here when native tunnel execution is added.

Expected filenames supported by `CoreBridge`:

- `libxray.so` for the Xray core, or `libv2ray.so` for the V2Ray core
- `libtun2socks.so` for the TUN-to-SOCKS routing layer

Android packages these `.so` files from `jniLibs/<abi>/` and exposes the matching device ABI through `applicationInfo.nativeLibraryDir` at runtime. A `.so` is not a standalone executable; use a real JNI/AAR wrapper with documented start/stop APIs, or ship a separate executable binary through a compliant extraction path.

Do not commit placeholder or fake `.so` files. Only add real, licensed binaries from a verified build pipeline.
