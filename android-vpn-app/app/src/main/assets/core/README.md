# Core asset staging

Use this folder only for metadata or verified runtime assets that support native tunnel integration.

The current runtime detection is focused on packaged Android native libraries under `app/src/main/jniLibs/<abi>/`. If a future integration loads executable artifacts from assets instead, it must copy the matching ABI file to app-private storage, verify it, mark it executable, and start it through a documented process path.

Do not add fake binary files. Real native binaries must come from a trusted, licensed build/release process.
