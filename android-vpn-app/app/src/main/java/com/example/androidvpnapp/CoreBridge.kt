package com.example.androidvpnapp

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import go.Seq
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import libv2ray.Libv2ray
import libv2ray.V2RayPoint
import libv2ray.V2RayVPNServiceSupportsSet

/**
 * Bridges app-selected profiles to the packaged gomobile V2Ray runtime.
 *
 * Android packages files from app/src/main/jniLibs/<abi>/ into the APK and extracts the
 * matching ABI at install time into applicationInfo.nativeLibraryDir. The runtime is provided
 * by libgojni.so; a separate libxray.so/libv2ray.so is intentionally not required because stale
 * standalone core files can be selected accidentally and break START. This bridge starts the
 * selected VLESS config through V2RayPoint.runLoop(), then launches the packaged tun2socks
 * binary with the Android VPN TUN file descriptor so device traffic is forwarded to the local
 * SOCKS inbound.
 */
class CoreBridge(private val context: Context) {
    private val configFile = File(context.filesDir, GENERATED_CONFIG_FILE)
    private var status: Status = Status.Stopped
    private var lastError: String? = null
    private var selectedRuntime: NativeLibrary? = null

    fun getStatus(profile: TunnelProfile? = null): Status {
        if (status == Status.Starting || status == Status.Running || status == Status.Error) return status
        if (profile == null) return Status.Stopped

        val install = detectNativeLibraries()
        return when {
            install.gojni == null -> Status.MissingGoJni
            install.tun2socks == null -> Status.MissingTun2Socks
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
        val tun2socks = available
            .filter { it.name == TUN2SOCKS_LIBRARY }
            .sortedBy { supportedAbiOrder.indexOf(it.abi).let { index -> if (index < 0) Int.MAX_VALUE else index } }
            .firstOrNull()
        val gojni = available
            .filter { it.name == GOJNI_LIBRARY }
            .sortedBy { supportedAbiOrder.indexOf(it.abi).let { index -> if (index < 0) Int.MAX_VALUE else index } }
            .firstOrNull()

        return NativeCoreInstall(tun2socks = tun2socks, gojni = gojni, discovered = available)
    }

    fun areRequiredLibrariesInstalled(): Boolean {
        val install = detectNativeLibraries()
        return install.tun2socks != null && install.gojni != null
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
            val configJson = profile.toXrayJson()
            TunnelProfile.validateXrayJson(configJson).getOrThrow()
            configFile.writeText(configJson)
            Log.i(TAG_CONFIG, "Generated and verified Xray config for server=${profile.server.id} (${profile.server.host}:${profile.server.port}) at ${configFile.absolutePath}.")
            Log.i(TAG_CONFIG, "VLESS/WS config parsed: network=${profile.server.type}, security=${profile.server.security}, path=${profile.server.wsPath}, hostHeader=${profile.server.hostHeader.ifBlank { profile.server.host }}.")
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
        Log.i(TAG_NATIVE, "Native library detection: gojni=${install.gojni?.displayPath ?: "missing"}, tun2socks=${install.tun2socks?.displayPath ?: "missing"}.")
        val gojni = install.gojni ?: return fail(Status.MissingGoJni, "Missing libgojni.so gomobile V2Ray runtime for ${EXPECTED_ABIS.joinToString()}.")
        val tun2socks = install.tun2socks ?: return fail(Status.MissingTun2Socks, "Missing libtun2socks.so for ${EXPECTED_ABIS.joinToString()}.")
        if (vpnInterface == null) {
            return fail(Status.Error, "Android VPN TUN interface was not established.")
        }

        status = Status.Starting
        selectedRuntime = gojni
        Log.i(TAG, "Prepared ${gojni.name} (${gojni.abi}) with ${tun2socks.name}; config=${generated.absolutePath}.")

        if (!NativeRuntimeAdapter.isStartAvailable()) {
            return fail(
                Status.StartApiNotWired,
                START_API_NOT_WIRED_MESSAGE
            )
        }

        return NativeRuntimeAdapter.start(context, gojni, tun2socks, generated, vpnInterface, protectSocket)
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
                NativeRuntimeAdapter.stop(selectedRuntime)
            }
    }

    fun stop() {
        if (status == Status.Starting || status == Status.Running || status == Status.Error) {
            NativeRuntimeAdapter.stop(selectedRuntime)
            Log.i(TAG, "Stopped native runtime bridge state.")
        }
        selectedRuntime = null
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
        listOf(TUN2SOCKS_LIBRARY, GOJNI_LIBRARY).map { name ->
            (abi to name) to "app/src/main/jniLibs/$abi/$name"
        }
    }.toMap()

    enum class Status(val label: String) {
        MissingTun2Socks("Missing tun2socks"),
        MissingGoJni("Missing V2Ray runtime"),
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
            runtime: NativeLibrary,
            tun2socks: NativeLibrary,
            configFile: File,
            vpnInterface: ParcelFileDescriptor,
            protectSocket: (Int) -> Boolean
        ): Result<Unit> = try {
            stop(runtime)
            Seq.touch()
            Seq.setContext(context.applicationContext)
            Libv2ray.touch()
            Libv2ray.initV2Env(context.filesDir.absolutePath)

            val configContent = configFile.readText()
            Log.i(TAG_CONFIG, "Testing generated Xray config before runtime start: ${configFile.absolutePath}.")
            Libv2ray.testConfig(configContent)
            Log.i(TAG_CONFIG, "Generated Xray config passed libv2ray validation.")

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

            Log.i(TAG_V2RAY, "Creating libv2ray point and starting runLoop thread.")
            val point = Libv2ray.newV2RayPoint(supportSet, false)
            point.setConfigureFileContent(configContent)
            point.setDomainName("")
            v2rayPoint = point
            val runLoopFailure = AtomicReference<Throwable?>()
            v2rayThread = Thread({
                try {
                    point.runLoop(false)
                } catch (error: UnsatisfiedLinkError) {
                    runLoopFailure.set(error)
                    Log.e(TAG_V2RAY, "V2Ray runLoop failed: unsatisfied link.", error)
                } catch (error: NoClassDefFoundError) {
                    runLoopFailure.set(error)
                    Log.e(TAG_V2RAY, "V2Ray runLoop failed: missing class.", error)
                } catch (error: IllegalStateException) {
                    runLoopFailure.set(error)
                    Log.e(TAG_V2RAY, "V2Ray runLoop failed: illegal state.", error)
                } catch (error: IOException) {
                    runLoopFailure.set(error)
                    Log.e(TAG_V2RAY, "V2Ray runLoop failed: I/O error.", error)
                } catch (error: Throwable) {
                    runLoopFailure.set(error)
                    Log.e(TAG_V2RAY, "V2Ray runLoop failed.", error)
                }
            }, "xray-run-loop").apply {
                isDaemon = true
                start()
            }

            Thread.sleep(700L)
            runLoopFailure.get()?.let { throw IllegalStateException("V2Ray run loop failed before routing started: ${it.message ?: it::class.java.simpleName}", it) }
            if (v2rayThread?.isAlive != true) {
                throw IllegalStateException("V2Ray run loop exited before routing started.")
            }

            Log.i(TAG_TUN2SOCKS, "Detaching Android TUN file descriptor for tun2socks.")
            val tunFd = vpnInterface.detachFd()
            detachedTunFd = tunFd
            tun2socksProcess = startTun2Socks(tun2socks, tunFd)
            closeDetachedTunFd()
            Log.i(TAG_V2RAY, "V2Ray start result: runLoop launched for ${runtime.name}.")
            Log.i(TAG_TUN2SOCKS, "tun2socks start result: process launched for ${tun2socks.name} with tun fd $tunFd.")
            Result.success(Unit)
        } catch (error: UnsatisfiedLinkError) {
            stop(runtime)
            Result.failure(IllegalStateException("Start failed: native library could not be loaded: ${error.message}", error))
        } catch (error: NoClassDefFoundError) {
            stop(runtime)
            Result.failure(IllegalStateException("Start failed: native runtime class is missing: ${error.message}", error))
        } catch (error: IllegalStateException) {
            stop(runtime)
            Result.failure(IllegalStateException("Start failed: ${error.message ?: "illegal native runtime state"}", error))
        } catch (error: IOException) {
            stop(runtime)
            Result.failure(IllegalStateException("Start failed: I/O error while starting native runtime: ${error.message}", error))
        } catch (error: Throwable) {
            stop(runtime)
            Result.failure(IllegalStateException("Start failed: ${error.message ?: error::class.java.simpleName}", error))
        }

        fun stop(runtime: NativeLibrary?) {
            runCatching { v2rayPoint?.stopLoop() }
                .onFailure { Log.w(TAG, "Failed to stop libv2ray point for ${runtime?.name.orEmpty()}.", it) }
            v2rayPoint = null
            v2rayThread?.interrupt()
            v2rayThread = null
            tun2socksProcess?.destroy()
            tun2socksProcess = null
            closeDetachedTunFd()
        }

        private fun closeDetachedTunFd() {
            val fd = detachedTunFd ?: return
            runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
                .onFailure { Log.w(TAG_TUN2SOCKS, "Failed to close detached TUN fd $fd in parent process.", it) }
            detachedTunFd = null
        }

        private fun startTun2Socks(tun2socks: NativeLibrary, tunFd: Int): Process {
            val executable = tun2socks.installedFile ?: error("tun2socks file is missing.")
            if (!executable.setExecutable(true, false) && !executable.canExecute()) {
                throw IOException("tun2socks is not executable at ${executable.absolutePath}.")
            }
            val command = listOf(
                executable.absolutePath,
                "--tunfd", tunFd.toString(),
                "--tunmtu", "1500",
                "--netif-ipaddr", "10.10.0.2",
                "--netif-netmask", "255.255.255.252",
                "--socks-server-addr", "127.0.0.1:10808",
                "--udpgw-remote-server-addr", "127.0.0.1:7300"
            )
            Log.i(TAG_TUN2SOCKS, "Starting tun2socks command: ${command.joinToString(" ")}.")
            val earlyOutput = StringBuilder()
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            Thread({
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (earlyOutput.length < MAX_TUN2SOCKS_LOG_CHARS) {
                            earlyOutput.appendLine(line)
                        }
                        Log.i(TAG_TUN2SOCKS, line)
                    }
                }
            }, "tun2socks-log").apply {
                isDaemon = true
                start()
            }
            Thread.sleep(350L)
            if (!process.isAlive) {
                throw IOException("tun2socks exited early with code ${process.exitValue()}: ${earlyOutput.toString().trim().ifBlank { "no output" }}")
            }
            return process
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
        private val EXPECTED_ABIS = listOf("arm64-v8a")
        private const val TUN2SOCKS_LIBRARY = "libtun2socks.so"
        private const val GOJNI_LIBRARY = "libgojni.so"
        private const val START_API_NOT_WIRED_MESSAGE =
            "Native runtime could not load libgojni/libv2ray wrappers."
        private const val MAX_TUN2SOCKS_LOG_CHARS = 4_000
    }
}
