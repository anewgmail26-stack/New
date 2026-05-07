package com.example.androidvpnapp

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

class TunnelCoreManager(private val context: Context) {
    private var status = CoreStatus.DISCONNECTED
    private var generatedConfigFile: File? = null

    fun getStatus(profile: TunnelProfile? = ConfigStore(context).loadSelectedProfile()): CoreStatus = when {
        profile == null -> CoreStatus.CONFIG_MISSING
        !areNativeCoreFilesInstalled() -> CoreStatus.CORE_NOT_INSTALLED
        status == CoreStatus.CONNECTING || status == CoreStatus.CONNECTED -> status
        else -> CoreStatus.READY
    }

    fun getStatusLabel(profile: TunnelProfile? = ConfigStore(context).loadSelectedProfile()): String = getStatus(profile).label

    fun areNativeCoreFilesInstalled(): Boolean {
        val coreRuntimeInstalled = nativeLibraryExists(XRAY_LIBRARY) ||
            nativeLibraryExists(V2RAY_LIBRARY) ||
            assetCoreExists(XRAY_LIBRARY) ||
            assetCoreExists(V2RAY_LIBRARY)
        val tunRoutingInstalled = nativeLibraryExists(TUN2SOCKS_LIBRARY) || assetCoreExists(TUN2SOCKS_LIBRARY)
        return coreRuntimeInstalled && tunRoutingInstalled
    }

    fun generateAndSaveConfig(profile: TunnelProfile?): Result<File> {
        if (profile == null) {
            status = CoreStatus.CONFIG_MISSING
            return Result.failure(IllegalStateException(CoreStatus.CONFIG_MISSING.label))
        }

        return runCatching {
            val configFile = File(context.filesDir, GENERATED_CONFIG_FILE)
            configFile.writeText(profile.toXrayJson())
            generatedConfigFile = configFile
            configFile
        }
    }

    fun start(profile: TunnelProfile?, vpnInterface: ParcelFileDescriptor?): Result<Unit> {
        if (profile == null) {
            status = CoreStatus.CONFIG_MISSING
            return Result.failure(IllegalStateException(CoreStatus.CONFIG_MISSING.label))
        }

        val configResult = generateAndSaveConfig(profile)
        if (configResult.isFailure) {
            return Result.failure(configResult.exceptionOrNull() ?: IllegalStateException(CoreStatus.CONFIG_MISSING.label))
        }

        if (!areNativeCoreFilesInstalled()) {
            status = CoreStatus.CORE_NOT_INSTALLED
            return Result.failure(IllegalStateException(CoreStatus.CORE_NOT_INSTALLED.label))
        }

        status = CoreStatus.CONNECTING
        val configFile = configResult.getOrThrow()
        // TODO: Connect the Android VpnService TUN file descriptor to the native routing stack.
        // TODO: Launch libxray.so or libv2ray.so with configFile.absolutePath when trusted binaries are packaged.
        // TODO: Attach libtun2socks.so to bridge the TUN interface to the local Xray/V2Ray SOCKS inbound.
        // TODO: Stream native Xray/V2Ray/tun2socks logs into app-visible diagnostics before enabling real traffic claims.
        Log.i(
            TAG,
            "Prepared native tunnel start. Config=${configFile.absolutePath}; TUN present=${vpnInterface != null}."
        )
        status = CoreStatus.CONNECTED
        return Result.success(Unit)
    }

    fun stop() {
        if (status == CoreStatus.CONNECTING || status == CoreStatus.CONNECTED) {
            Log.i(TAG, "Stopping native tunnel placeholder state.")
        }
        // TODO: Stop the real Xray/V2Ray process and tun2socks routing layer when native execution is added.
        status = CoreStatus.DISCONNECTED
    }

    fun isRunning(): Boolean = status == CoreStatus.CONNECTING || status == CoreStatus.CONNECTED

    private fun nativeLibraryExists(fileName: String): Boolean =
        File(context.applicationInfo.nativeLibraryDir, fileName).isFile

    private fun assetCoreExists(fileName: String): Boolean = try {
        context.assets.open("core/$fileName").close()
        true
    } catch (_: Exception) {
        false
    }

    enum class CoreStatus(val label: String) {
        CORE_NOT_INSTALLED("Core not installed"),
        CONFIG_MISSING("Config missing"),
        READY("Ready"),
        CONNECTING("Connecting"),
        CONNECTED("Connected"),
        DISCONNECTED("Disconnected")
    }

    companion object {
        private const val TAG = "TunnelCoreManager"
        private const val GENERATED_CONFIG_FILE = "xray-generated-config.json"
        private const val XRAY_LIBRARY = "libxray.so"
        private const val V2RAY_LIBRARY = "libv2ray.so"
        private const val TUN2SOCKS_LIBRARY = "libtun2socks.so"
    }
}
