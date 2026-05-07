package com.example.androidvpnapp

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Bridges app-selected profiles to a packaged Xray/V2Ray core.
 *
 * Android packages files from app/src/main/jniLibs/<abi>/ into the APK and extracts the
 * matching ABI at install time into applicationInfo.nativeLibraryDir (for example,
 * /data/app/.../lib/arm64). Kotlin cannot execute a .so with ProcessBuilder; a .so must
 * either expose JNI functions loaded with System.loadLibrary(...), or the project must ship
 * a separate executable binary/AAR wrapper that knows how to start and stop the native core.
 *
 * This class deliberately does not invent JNI method names. The NativeRuntimeAdapter below is
 * the single TODO seam for a future, real Xray/V2Ray/tun2socks AAR/JNI wrapper. Until that
 * adapter is implemented, start() fails gracefully even when native libraries are present so
 * the UI never claims real traffic is connected through placeholder code.
 */
class CoreBridge(private val context: Context) {
    private val configFile = File(context.filesDir, GENERATED_CONFIG_FILE)
    private var status: Status = Status.Stopped
    private var lastError: String? = null
    private var selectedCore: NativeLibrary? = null

    fun getStatus(profile: TunnelProfile? = null): Status = when {
        status == Status.Starting || status == Status.Running || status == Status.Error -> status
        profile == null -> Status.Stopped
        detectNativeLibraries().core == null -> Status.MissingCore
        detectNativeLibraries().tun2socks == null -> Status.MissingTun2Socks
        detectNativeLibraries().gojni == null -> Status.MissingGoJni
        !isNativeRuntimeStartAvailable() -> Status.StartApiNotWired
        else -> Status.Ready
    }

    fun getStatusLabel(profile: TunnelProfile? = null): String = getStatus(profile).label

    fun getLastError(): String? = lastError

    fun detectNativeLibraries(): NativeCoreInstall {
        val installed = mutableListOf<NativeLibrary>()
        val nativeDir = File(context.applicationInfo.nativeLibraryDir ?: "")
        val sourceAbiLibs = expectedSourceLibraries()

        EXPECTED_ABIS.forEach { abi ->
            CORE_LIBRARY_NAMES.forEach { name ->
                installed += NativeLibrary(
                    name = name,
                    abi = abi,
                    installedFile = File(nativeDir, name).takeIf { it.isFile },
                    sourcePath = sourceAbiLibs[abi to name]
                )
            }
            installed += NativeLibrary(
                name = TUN2SOCKS_LIBRARY,
                abi = abi,
                installedFile = File(nativeDir, TUN2SOCKS_LIBRARY).takeIf { it.isFile },
                sourcePath = sourceAbiLibs[abi to TUN2SOCKS_LIBRARY]
            )
            installed += NativeLibrary(
                name = GOJNI_LIBRARY,
                abi = abi,
                installedFile = File(nativeDir, GOJNI_LIBRARY).takeIf { it.isFile },
                sourcePath = sourceAbiLibs[abi to GOJNI_LIBRARY]
            )
        }

        val supportedAbiOrder = Build.SUPPORTED_ABIS.toList().ifEmpty { EXPECTED_ABIS }
        val available = installed.filter { it.isInstalled }
        val core = available
            .filter { it.name in CORE_LIBRARY_NAMES }
            .sortedWith(compareBy<NativeLibrary> { supportedAbiOrder.indexOf(it.abi).let { index -> if (index < 0) Int.MAX_VALUE else index } }
                .thenBy { CORE_LIBRARY_NAMES.indexOf(it.name) })
            .firstOrNull()
        val tun2socks = available
            .filter { it.name == TUN2SOCKS_LIBRARY }
            .sortedBy { supportedAbiOrder.indexOf(it.abi).let { index -> if (index < 0) Int.MAX_VALUE else index } }
            .firstOrNull()
        val gojni = available
            .filter { it.name == GOJNI_LIBRARY }
            .sortedBy { supportedAbiOrder.indexOf(it.abi).let { index -> if (index < 0) Int.MAX_VALUE else index } }
            .firstOrNull()

        return NativeCoreInstall(core = core, tun2socks = tun2socks, gojni = gojni, discovered = available)
    }

    fun areRequiredLibrariesInstalled(): Boolean {
        val install = detectNativeLibraries()
        return install.core != null && install.tun2socks != null && install.gojni != null
    }

    fun isNativeRuntimeStartAvailable(): Boolean = NativeRuntimeAdapter().isStartAvailable()

    fun generateAndSaveConfig(profile: TunnelProfile?): Result<File> {
        if (profile == null) {
            status = Status.Stopped
            return Result.failure(IllegalStateException("Select a server and payload profile first."))
        }

        return runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writeText(profile.toXrayJson())
            configFile
        }.onFailure { error ->
            status = Status.Error
            lastError = "Could not write Xray config: ${error.message}"
        }
    }

    fun start(profile: TunnelProfile?, vpnInterface: ParcelFileDescriptor?): Result<Unit> {
        val generated = generateAndSaveConfig(profile).getOrElse { return Result.failure(it) }
        val install = detectNativeLibraries()
        val core = install.core ?: return fail(Status.MissingCore, "Missing libxray.so or libv2ray.so for ${EXPECTED_ABIS.joinToString()}.")
        val tun2socks = install.tun2socks ?: return fail(Status.MissingTun2Socks, "Missing libtun2socks.so for ${EXPECTED_ABIS.joinToString()}.")
        val gojni = install.gojni ?: return fail(Status.MissingGoJni, "Missing libgojni.so for ${EXPECTED_ABIS.joinToString()}.")
        if (vpnInterface == null) {
            return fail(Status.Error, "Android VPN TUN interface was not established.")
        }

        status = Status.Starting
        selectedCore = core
        Log.i(TAG, "Prepared ${core.name} (${core.abi}) with ${tun2socks.name} and ${gojni.name}; config=${generated.absolutePath}.")

        val adapter = NativeRuntimeAdapter()
        if (!adapter.isStartAvailable()) {
            return fail(
                Status.StartApiNotWired,
                START_API_NOT_WIRED_MESSAGE
            )
        }

        return adapter.start(core, tun2socks, generated, vpnInterface)
            .onSuccess {
                status = Status.Running
                lastError = null
            }
            .onFailure { error ->
                status = Status.Error
                lastError = error.message
            }
    }

    fun stop() {
        if (status == Status.Starting || status == Status.Running || status == Status.Error) {
            NativeRuntimeAdapter().stop(selectedCore)
            Log.i(TAG, "Stopped native core bridge state.")
        }
        selectedCore = null
        status = Status.Stopped
        lastError = null
    }

    fun isRunning(): Boolean = status == Status.Running

    private fun fail(newStatus: Status, message: String): Result<Unit> {
        status = newStatus
        lastError = message
        Log.w(TAG, message)
        return Result.failure(IllegalStateException(message))
    }

    /**
     * Source-tree paths are documented for maintainers. At runtime, Android normally exposes
     * packaged .so files through nativeLibraryDir after installing the APK; sourcePath is metadata
     * for the exact jniLibs locations this bridge expects before packaging.
     */
    private fun expectedSourceLibraries(): Map<Pair<String, String>, String> = EXPECTED_ABIS.flatMap { abi ->
        (CORE_LIBRARY_NAMES + TUN2SOCKS_LIBRARY + GOJNI_LIBRARY).map { name ->
            (abi to name) to "app/src/main/jniLibs/$abi/$name"
        }
    }.toMap()

    enum class Status(val label: String) {
        MissingCore("Missing Xray/V2Ray core"),
        MissingTun2Socks("Missing tun2socks"),
        MissingGoJni("Missing gojni"),
        StartApiNotWired("Native core files present, start API not wired"),
        Ready("Ready"),
        Starting("Starting"),
        Running("Running"),
        Stopped("Stopped"),
        Error("Core error")
    }

    data class NativeLibrary(
        val name: String,
        val abi: String,
        val installedFile: File?,
        val sourcePath: String?
    ) {
        val isInstalled: Boolean get() = installedFile?.isFile == true
        val displayPath: String get() = installedFile?.absolutePath ?: sourcePath.orEmpty()
    }

    data class NativeCoreInstall(
        val core: NativeLibrary?,
        val tun2socks: NativeLibrary?,
        val gojni: NativeLibrary?,
        val discovered: List<NativeLibrary>
    )

    /** TODO: Replace with calls provided by a real Xray/V2Ray/tun2socks JNI/AAR integration. */
    private class NativeRuntimeAdapter {
        fun isStartAvailable(): Boolean = false

        fun start(
            core: NativeLibrary,
            tun2socks: NativeLibrary,
            configFile: File,
            vpnInterface: ParcelFileDescriptor
        ): Result<Unit> = Result.failure(
            UnsupportedOperationException(
                "No native runtime adapter is wired for ${core.name}, ${tun2socks.name}, ${configFile.name}, fd=${vpnInterface.fd}."
            )
        )

        fun stop(core: NativeLibrary?) {
            // No-op until a real adapter owns native process/JNI lifecycle.
        }
    }

    companion object {
        private const val TAG = "CoreBridge"
        private const val GENERATED_CONFIG_FILE = "xray-generated-config.json"
        private val EXPECTED_ABIS = listOf("arm64-v8a", "armeabi-v7a")
        private val CORE_LIBRARY_NAMES = listOf("libxray.so", "libv2ray.so")
        private const val TUN2SOCKS_LIBRARY = "libtun2socks.so"
        private const val GOJNI_LIBRARY = "libgojni.so"
        private const val START_API_NOT_WIRED_MESSAGE =
            "Native core files present, start API not wired. Add a documented Java/Kotlin JNI wrapper, AAR, or source API before enabling traffic."
    }
}
