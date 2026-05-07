package com.example.androidvpnapp

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

class TunnelCoreManager(private val context: Context) {
    private val coreBridge = CoreBridge(context.applicationContext)

    fun getStatus(profile: TunnelProfile? = ConfigStore(context).loadSelectedProfile()): CoreStatus =
        if (profile == null) CoreStatus.CONFIG_MISSING else coreBridge.getStatus(profile).toManagerStatus()

    fun getStatusLabel(profile: TunnelProfile? = ConfigStore(context).loadSelectedProfile()): String = getStatus(profile).label

    fun getLastError(): String? = coreBridge.getLastError()

    fun areNativeCoreFilesInstalled(): Boolean = coreBridge.areRequiredLibrariesInstalled()

    fun isNativeRuntimeStartAvailable(): Boolean = coreBridge.isNativeRuntimeStartAvailable()

    fun describeNativeCoreInstall(): String {
        val install = coreBridge.detectNativeLibraries()
        val core = install.core?.displayPath ?: "Missing libxray.so/libv2ray.so"
        val tun2socks = install.tun2socks?.displayPath ?: "Missing libtun2socks.so"
        val gojni = install.gojni?.displayPath ?: "Missing libgojni.so"
        return "Xray core: $core\nTUN routing: $tun2socks\nGo JNI: $gojni"
    }

    fun generateAndSaveConfig(profile: TunnelProfile?): Result<File> = coreBridge.generateAndSaveConfig(profile)

    fun start(profile: TunnelProfile?, vpnInterface: ParcelFileDescriptor?, protectSocket: (Int) -> Boolean = { false }): Result<Unit> {
        if (profile == null) {
            return Result.failure(IllegalStateException(CoreStatus.CONFIG_MISSING.label))
        }

        val preflightStatus = getStatus(profile)
        if (preflightStatus == CoreStatus.CORE_NOT_INSTALLED ||
            preflightStatus == CoreStatus.TUN2SOCKS_NOT_INSTALLED ||
            preflightStatus == CoreStatus.GOJNI_NOT_INSTALLED ||
            preflightStatus == CoreStatus.START_API_NOT_WIRED
        ) {
            return Result.failure(IllegalStateException(preflightStatus.label))
        }

        val result = coreBridge.start(profile, vpnInterface, protectSocket)
        if (result.isFailure) {
            Log.w(TAG, "CoreBridge refused to start: ${coreBridge.getLastError()}")
        }
        return result
    }

    fun stop() = coreBridge.stop()

    fun isRunning(): Boolean = coreBridge.isRunning()

    private fun CoreBridge.Status.toManagerStatus(): CoreStatus = when (this) {
        CoreBridge.Status.MissingCore -> CoreStatus.CORE_NOT_INSTALLED
        CoreBridge.Status.MissingTun2Socks -> CoreStatus.TUN2SOCKS_NOT_INSTALLED
        CoreBridge.Status.MissingGoJni -> CoreStatus.GOJNI_NOT_INSTALLED
        CoreBridge.Status.StartApiNotWired -> CoreStatus.START_API_NOT_WIRED
        CoreBridge.Status.Ready -> CoreStatus.READY
        CoreBridge.Status.Starting -> CoreStatus.CONNECTING
        CoreBridge.Status.Running -> CoreStatus.CONNECTED
        CoreBridge.Status.Stopped -> CoreStatus.DISCONNECTED
        CoreBridge.Status.Error -> CoreStatus.ERROR
    }

    enum class CoreStatus(val label: String) {
        CORE_NOT_INSTALLED("Missing Xray/V2Ray core"),
        TUN2SOCKS_NOT_INSTALLED("Missing tun2socks"),
        GOJNI_NOT_INSTALLED("Missing gojni"),
        CONFIG_MISSING("Config missing"),
        START_API_NOT_WIRED("Native runtime unavailable"),
        READY("Ready"),
        CONNECTING("Starting"),
        CONNECTED("Running"),
        DISCONNECTED("Stopped"),
        ERROR("Core error")
    }

    companion object {
        private const val TAG = "TunnelCoreManager"
    }
}
