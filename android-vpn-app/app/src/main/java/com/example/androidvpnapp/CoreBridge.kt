package com.example.androidvpnapp

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import go.Seq
import java.io.File
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet

/**
 * Bridges app-selected profiles to a packaged Xray/V2Ray core.
 *
 * Android packages files from app/src/main/jniLibs/<abi>/ into the APK and extracts the
 * matching ABI at install time into applicationInfo.nativeLibraryDir. This bridge loads the
 * gomobile libv2ray JNI surface from libgojni.so, starts the selected VLESS config through
 * V2RayPoint.runLoop(), then launches the packaged tun2socks binary with the Android VPN TUN
 * file descriptor so device traffic is forwarded to the local SOCKS inbound.
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

    fun isNativeRuntimeStartAvailable(): Boolean = NativeRuntimeAdapter.isStartAvailable()

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

    fun start(profile: TunnelProfile?, vpnInterface: ParcelFileDescriptor?, protectSocket: (Int) -> Boolean = { false }): Result<Unit> {
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

        if (!NativeRuntimeAdapter.isStartAvailable()) {
            return fail(
                Status.StartApiNotWired,
                START_API_NOT_WIRED_MESSAGE
            )
        }

        return NativeRuntimeAdapter.start(context, core, tun2socks, generated, vpnInterface, protectSocket)
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
            NativeRuntimeAdapter.stop(selectedCore)
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
        StartApiNotWired("Native runtime unavailable"),
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

    private object NativeRuntimeAdapter {
        private var v2rayPoint: V2RayPoint? = null
        private var v2rayThread: Thread? = null
        private var tun2socksProcess: Process? = null
        private var detachedTunFd: Int? = null

        fun isStartAvailable(): Boolean = runCatching {
            Seq.touch()
            Libv2ray.touch()
            true
        }.getOrDefault(false)

        fun start(
            context: Context,
            core: NativeLibrary,
            tun2socks: NativeLibrary,
            configFile: File,
            vpnInterface: ParcelFileDescriptor,
            protectSocket: (Int) -> Boolean
        ): Result<Unit> = runCatching {
            stop(core)
            Seq.touch()
            Seq.setContext(context.applicationContext)
            Libv2ray.touch()
            Libv2ray.initV2Env(context.filesDir.absolutePath)

            val configContent = configFile.readText()
            Libv2ray.testConfig(configContent)

            val supportSet = object : V2RayVPNServiceSupportsSet {
                override fun setup(conf: String): Long = 0L
                override fun prepare(): Long = 0L
                override fun shutdown(): Long = 0L
                override fun protect(socket: Long): Boolean = protectSocket(socket.toInt())
                override fun onEmitStatus(code: Long, message: String): Long {
                    Log.i(TAG, "libv2ray status[$code]: $message")
                    return 0L
                }
            }

            val point = Libv2ray.newV2RayPoint(supportSet, false)
            point.setConfigureFileContent(configContent)
            point.setDomainName("")
            v2rayPoint = point
            v2rayThread = Thread({ point.runLoop(false) }, "xray-run-loop").apply {
                isDaemon = true
                start()
            }

            Thread.sleep(450L)
            val tunFd = vpnInterface.detachFd()
            detachedTunFd = tunFd
            tun2socksProcess = startTun2Socks(tun2socks, tunFd)
            Log.i(TAG, "Started ${core.name} through libgojni and ${tun2socks.name} with tun fd $tunFd.")
        }

        fun stop(core: NativeLibrary?) {
            runCatching { v2rayPoint?.stopLoop() }
                .onFailure { Log.w(TAG, "Failed to stop libv2ray point for ${core?.name.orEmpty()}.", it) }
            v2rayPoint = null
            v2rayThread?.interrupt()
            v2rayThread = null
            tun2socksProcess?.destroy()
            tun2socksProcess = null
            detachedTunFd = null
        }

        private fun startTun2Socks(tun2socks: NativeLibrary, tunFd: Int): Process {
            val executable = tun2socks.installedFile ?: error("tun2socks file is missing.")
            executable.setExecutable(true, false)
            val command = listOf(
                executable.absolutePath,
                "--logger", "stdout",
                "--loglevel", "warning",
                "--tunfd", tunFd.toString(),
                "--tunmtu", "1500",
                "--netif-ipaddr", "10.10.0.2",
                "--netif-netmask", "255.255.255.252",
                "--socks-server-addr", "127.0.0.1:10808",
                "--udpgw-remote-server-addr", "127.0.0.1:7300"
            )
            return ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
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
            "Native runtime could not load libgojni/libv2ray wrappers."
    }
}
