package com.example.androidvpnapp

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import go.Seq
import java.io.File
import java.io.IOException
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

    fun getStatus(profile: TunnelProfile? = null): Status {
        if (status == Status.Starting || status == Status.Running || status == Status.Error) return status
        if (profile == null) return Status.Stopped

        val install = detectNativeLibraries()
        return when {
            install.core == null -> Status.MissingCore
            install.tun2socks == null -> Status.MissingTun2Socks
            install.gojni == null -> Status.MissingGoJni
            else -> Status.Ready
        }
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

    fun isNativeRuntimePackaged(): Boolean = try {
        Class.forName("go.Seq", false, javaClass.classLoader)
        Class.forName("libv2ray.Libv2ray", false, javaClass.classLoader)
        true
    } catch (error: ClassNotFoundException) {
        lastError = "Native runtime wrapper class is missing: ${error.message}"
        false
    }

    fun generateAndSaveConfig(profile: TunnelProfile?): Result<File> {
        if (profile == null) {
            status = Status.Stopped
            return Result.failure(IllegalStateException("Select a server and payload profile first."))
        }

        return runCatching {
            configFile.parentFile?.mkdirs()
            configFile.writeText(profile.toXrayJson())
            Log.i(TAG_CONFIG, "Generated Xray config for server=${profile.server.id} (${profile.server.host}:${profile.server.port}) at ${configFile.absolutePath}.")
            configFile
        }.onFailure { error ->
            status = Status.Error
            lastError = "Could not write Xray config: ${error.message}"
        }
    }

    fun start(profile: TunnelProfile?, vpnInterface: ParcelFileDescriptor?, protectSocket: (Int) -> Boolean = { false }): Result<Unit> {
        val selectedProfile = profile ?: return fail(Status.Error, "no selected profile.")
        Log.i(TAG_PROFILE, "Selected server/profile: id=${selectedProfile.server.id}, name=${selectedProfile.server.name}, host=${selectedProfile.server.host}:${selectedProfile.server.port}, type=${selectedProfile.server.type}, security=${selectedProfile.server.security}, payload=${selectedProfile.payloadTweak.id}.")
        val generated = generateAndSaveConfig(selectedProfile).getOrElse { return Result.failure(toStartFailure(it)) }
        val install = detectNativeLibraries()
        Log.i(TAG_NATIVE, "Native library detection: core=${install.core?.displayPath ?: "missing"}, tun2socks=${install.tun2socks?.displayPath ?: "missing"}, gojni=${install.gojni?.displayPath ?: "missing"}.")
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
                Log.i(TAG_V2RAY, "V2Ray start result: success for config=${generated.absolutePath}.")
                Log.i(TAG_TUN2SOCKS, "tun2socks start result: success for ${tun2socks.displayPath}.")
            }
            .onFailure { error ->
                val failure = toStartFailure(error)
                status = Status.Error
                lastError = failure.message
                Log.e(TAG_V2RAY, "V2Ray start result: failed: $lastError", error)
                Log.e(TAG_TUN2SOCKS, "tun2socks start result: failed or released after V2Ray failure: $lastError", error)
                NativeRuntimeAdapter.stop(selectedCore)
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
        val failure = IllegalStateException("Start failed: $message")
        lastError = failure.message
        Log.w(TAG, failure.message.orEmpty())
        return Result.failure(failure)
    }

    private fun toStartFailure(error: Throwable): Throwable {
        val reason = when (error) {
            is UnsatisfiedLinkError -> "native library could not be loaded: ${error.message}"
            is NoClassDefFoundError -> "native runtime class is missing: ${error.message}"
            is IllegalStateException -> error.message ?: "illegal native runtime state"
            is IOException -> "I/O error while starting native runtime: ${error.message}"
            else -> error.message ?: error::class.java.simpleName
        }
        return IllegalStateException(if (reason.startsWith("Start failed:")) reason else "Start failed: $reason", error)
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

        fun isStartAvailable(): Boolean = try {
            Seq.touch()
            Libv2ray.touch()
            Log.i(TAG_NATIVE, "Native runtime API detected: libgojni/libv2ray wrappers are loadable.")
            true
        } catch (error: UnsatisfiedLinkError) {
            Log.e(TAG_NATIVE, "Native runtime API detection failed: unsatisfied link.", error)
            false
        } catch (error: NoClassDefFoundError) {
            Log.e(TAG_NATIVE, "Native runtime API detection failed: missing class.", error)
            false
        } catch (error: IllegalStateException) {
            Log.e(TAG_NATIVE, "Native runtime API detection failed: illegal state.", error)
            false
        } catch (error: IOException) {
            Log.e(TAG_NATIVE, "Native runtime API detection failed: I/O error.", error)
            false
        } catch (error: Throwable) {
            Log.e(TAG_NATIVE, "Native runtime API detection failed.", error)
            false
        }

        fun start(
            context: Context,
            core: NativeLibrary,
            tun2socks: NativeLibrary,
            configFile: File,
            vpnInterface: ParcelFileDescriptor,
            protectSocket: (Int) -> Boolean
        ): Result<Unit> = try {
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
            v2rayThread = Thread({
                try {
                    point.runLoop(false)
                } catch (error: UnsatisfiedLinkError) {
                    Log.e(TAG_V2RAY, "V2Ray runLoop failed: unsatisfied link.", error)
                } catch (error: NoClassDefFoundError) {
                    Log.e(TAG_V2RAY, "V2Ray runLoop failed: missing class.", error)
                } catch (error: IllegalStateException) {
                    Log.e(TAG_V2RAY, "V2Ray runLoop failed: illegal state.", error)
                } catch (error: IOException) {
                    Log.e(TAG_V2RAY, "V2Ray runLoop failed: I/O error.", error)
                } catch (error: Throwable) {
                    Log.e(TAG_V2RAY, "V2Ray runLoop failed.", error)
                }
            }, "xray-run-loop").apply {
                isDaemon = true
                start()
            }

            Thread.sleep(450L)
            val tunFd = vpnInterface.detachFd()
            detachedTunFd = tunFd
            tun2socksProcess = startTun2Socks(tun2socks, tunFd)
            Log.i(TAG_V2RAY, "V2Ray start result: runLoop launched for ${core.name}.")
            Log.i(TAG_TUN2SOCKS, "tun2socks start result: process launched for ${tun2socks.name} with tun fd $tunFd.")
            Result.success(Unit)
        } catch (error: UnsatisfiedLinkError) {
            stop(core)
            Result.failure(IllegalStateException("Start failed: native library could not be loaded: ${error.message}", error))
        } catch (error: NoClassDefFoundError) {
            stop(core)
            Result.failure(IllegalStateException("Start failed: native runtime class is missing: ${error.message}", error))
        } catch (error: IllegalStateException) {
            stop(core)
            Result.failure(IllegalStateException("Start failed: ${error.message ?: "illegal native runtime state"}", error))
        } catch (error: IOException) {
            stop(core)
            Result.failure(IllegalStateException("Start failed: I/O error while starting native runtime: ${error.message}", error))
        } catch (error: Throwable) {
            stop(core)
            Result.failure(IllegalStateException("Start failed: ${error.message ?: error::class.java.simpleName}", error))
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
        private const val TAG_PROFILE = "VpnProfile"
        private const val TAG_CONFIG = "XrayConfig"
        private const val TAG_NATIVE = "NativeRuntime"
        private const val TAG_V2RAY = "V2RayStart"
        private const val TAG_TUN2SOCKS = "Tun2SocksStart"
        private const val GENERATED_CONFIG_FILE = "xray-generated-config.json"
        private val EXPECTED_ABIS = listOf("arm64-v8a", "armeabi-v7a")
        private val CORE_LIBRARY_NAMES = listOf("libxray.so", "libv2ray.so")
        private const val TUN2SOCKS_LIBRARY = "libtun2socks.so"
        private const val GOJNI_LIBRARY = "libgojni.so"
        private const val START_API_NOT_WIRED_MESSAGE =
            "Native runtime could not load libgojni/libv2ray wrappers."
    }
}
