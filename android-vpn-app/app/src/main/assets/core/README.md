# Core asset staging

Use this folder only for metadata or verified runtime assets that support the native tunnel core integration.

If a future integration loads executable core artifacts from assets instead of `jniLibs`, the code expects these names:

- `libxray.so`
- `libv2ray.so`
- `libtun2socks.so`

Do not add fake binary files. Real native binaries must come from a trusted, licensed build/release process.
