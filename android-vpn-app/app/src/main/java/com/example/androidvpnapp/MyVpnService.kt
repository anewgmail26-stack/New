package com.example.androidvpnapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import java.io.IOException

class MyVpnService : VpnService() {
    private val stateLock = Any()
    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var tunnelCoreManager: TunnelCoreManager
    private var serviceState = ServiceState.Disconnected
    private var connectThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN service created.")
        tunnelCoreManager = TunnelCoreManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "VPN service start command received: action=${intent?.action}, flags=$flags, startId=$startId.")
        when (intent?.action) {
            ACTION_CONNECT -> handleConnectAction()
            ACTION_DISCONNECT -> handleDisconnectAction()
            else -> Log.w(TAG, "Ignoring unknown VPN service action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "VPN service is being destroyed; releasing VPN and native resources.")
        val shouldBroadcastDisconnect = synchronized(stateLock) {
            serviceState == ServiceState.Connecting || serviceState == ServiceState.Connected || serviceState == ServiceState.Stopping
        }
        stopTunnelAndTun()
        stopForegroundSafely()
        if (shouldBroadcastDisconnect) {
            setState(ServiceState.Disconnected)
        }
        super.onDestroy()
    }

    private fun handleConnectAction() {
        synchronized(stateLock) {
            when (serviceState) {
                ServiceState.Connecting, ServiceState.Connected -> {
                    Log.i(TAG, "Ignoring duplicate connect request while state=$serviceState.")
                    broadcastStateLocked(serviceState)
                    return
                }
                ServiceState.Stopping -> {
                    Log.i(TAG, "Ignoring connect request while stop is in progress.")
                    broadcastStateLocked(serviceState)
                    return
                }
                ServiceState.Disconnected, ServiceState.Failed -> {
                    serviceState = ServiceState.Connecting
                }
            }
        }

        try {
            startForegroundSafely(ServiceState.Connecting)
        } catch (error: Throwable) {
            failConnect(STEP_FOREGROUND_NOTIFICATION, sanitizeError(error.message ?: error::class.java.simpleName), error)
            return
        }
        setState(ServiceState.Connecting)
        connectThread = Thread({ connectInBackground() }, "vpn-connect").apply { start() }
    }

    private fun handleDisconnectAction() {
        synchronized(stateLock) {
            when (serviceState) {
                ServiceState.Disconnected -> {
                    Log.i(TAG, "Ignoring duplicate disconnect request while already disconnected.")
                    broadcastStateLocked(ServiceState.Disconnected)
                    stopSelf()
                    return
                }
                ServiceState.Stopping -> {
                    Log.i(TAG, "Ignoring duplicate disconnect request while stop is already in progress.")
                    broadcastStateLocked(ServiceState.Stopping)
                    return
                }
                ServiceState.Connecting, ServiceState.Connected, ServiceState.Failed -> {
                    serviceState = ServiceState.Stopping
                }
            }
        }

        setState(ServiceState.Stopping)
        Thread({
            stopTunnelAndTun()
            stopForegroundSafely()
            setState(ServiceState.Disconnected)
            stopSelf()
        }, "vpn-disconnect").start()
    }

    private fun connectInBackground() {
        try {
            val configStore = ConfigStore(applicationContext)
            val profile = configStore.loadSelectedProfile()
            Log.i(TAG, "VPN connect flow started; selectedProfilePresent=${profile != null}.")

            if (profile == null) {
                failConnect(STEP_XRAY_CONFIG_GENERATE, TunnelCoreManager.CoreStatus.CONFIG_MISSING.label, null)
                return
            }

            Log.i(
                TAG_PROFILE,
                "Config parsed: id=${profile.server.id}, protocol=vless, host=${profile.server.host}, port=${profile.server.port}, network=${profile.server.type}, security=${profile.server.security}, wsPath=${profile.server.wsPath}, wsHost=${profile.server.hostHeader.ifBlank { profile.server.host }}."
            )

            runStep(STEP_TUN_ESTABLISH) { ensureTunInterface(configStore) }.getOrElse { error ->
                failConnect(STEP_TUN_ESTABLISH, error.message ?: "Android VPN TUN interface failed", error)
                return
            }

            runStep(STEP_XRAY_CONFIG_GENERATE) { tunnelCoreManager.generateAndSaveConfig(profile).getOrThrow() }.getOrElse { error ->
                failConnect(STEP_XRAY_CONFIG_GENERATE, error.message ?: "Xray config generation failed", error)
                return
            }

            val coreStatus = tunnelCoreManager.getStatus(profile)
            Log.i(TAG, "Native runtime preflight status: $coreStatus.")
            if (coreStatus == TunnelCoreManager.CoreStatus.TUN2SOCKS_NOT_INSTALLED ||
                coreStatus == TunnelCoreManager.CoreStatus.GOJNI_NOT_INSTALLED ||
                coreStatus == TunnelCoreManager.CoreStatus.INVALID_NATIVE_RUNTIME ||
                coreStatus == TunnelCoreManager.CoreStatus.START_API_NOT_WIRED
            ) {
                failConnect(STEP_XRAY_CORE_START, coreStatus.label, null)
                return
            }

            val tun = vpnInterface
            if (tun == null) {
                failConnect(STEP_TUN_ESTABLISH, "Android VPN TUN interface was not established.", null)
                return
            }

            Log.i(TAG, "Starting libv2ray and tun2socks for Android TUN -> SOCKS 127.0.0.1:10808 -> VLESS outbound forwarding.")
            val result = tunnelCoreManager.start(profile, tun) { socket -> protect(socket) }
            if (result.isFailure) {
                val failure = result.exceptionOrNull()
                val step = failureStep(failure)
                failConnect(step, failure?.message ?: "native runtime did not start", failure)
                return
            }

            synchronized(stateLock) {
                if (serviceState == ServiceState.Stopping || serviceState == ServiceState.Disconnected) {
                    Log.i(TAG, "Connect completed after stop was requested; tearing started tunnel down.")
                    stopTunnelAndTun()
                    return
                }
                serviceState = ServiceState.Connected
                vpnInterface = null // CoreBridge detached the TUN fd for tun2socks; the process now owns it.
            }
            Log.i(TAG, "Native runtime and TUN routing started successfully.")
            startForegroundSafely(ServiceState.Connected)
            setState(ServiceState.Connected)
        } catch (error: UnsatisfiedLinkError) {
            failConnect(STEP_XRAY_CORE_START, "native library could not be loaded: ${error.message}", error)
        } catch (error: NoClassDefFoundError) {
            failConnect(STEP_XRAY_CORE_START, "native runtime class is missing: ${error.message}", error)
        } catch (error: IllegalStateException) {
            failConnect(STEP_XRAY_CORE_START, error.message ?: "illegal VPN service state", error)
        } catch (error: IOException) {
            failConnect(STEP_TUN2SOCKS_START, "I/O error while starting VPN: ${error.message}", error)
        } catch (error: Throwable) {
            failConnect(STEP_XRAY_CORE_START, error.message ?: error::class.java.simpleName, error)
        }
    }

    private fun <T> runStep(step: String, block: () -> T): Result<T> {
        Log.i(TAG, "Starting connect step $step.")
        return runCatching(block)
            .onSuccess { Log.i(TAG, "Completed connect step $step.") }
            .onFailure { Log.e(TAG, "Failed connect step $step: ${sanitizeError(it.message ?: it::class.java.simpleName)}", it) }
    }

    private fun failureStep(error: Throwable?): String {
        val message = error?.message.orEmpty()
        return when {
            message.contains(STEP_XRAY_CONFIG_TEST) -> STEP_XRAY_CONFIG_TEST
            message.contains(STEP_TUN2SOCKS_START) || message.contains("tun2socks", ignoreCase = true) -> STEP_TUN2SOCKS_START
            message.contains(STEP_XRAY_CORE_START) || message.contains("V2Ray", ignoreCase = true) || message.contains("native", ignoreCase = true) -> STEP_XRAY_CORE_START
            message.contains(STEP_XRAY_CONFIG_GENERATE) || message.contains("config", ignoreCase = true) -> STEP_XRAY_CONFIG_GENERATE
            else -> STEP_XRAY_CORE_START
        }
    }

    private fun ensureTunInterface(configStore: ConfigStore) {
        if (vpnInterface != null) {
            Log.i(TAG, "Reusing existing Android VPN TUN interface while connecting.")
            return
        }

        Log.i(TAG, "Establishing Android VPN TUN interface.")
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(VPN_MTU)
            .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
            .addRoute("0.0.0.0", 0)

        if (configStore.loadDnsEnabled()) {
            Log.i(TAG, "Adding DNS servers to TUN configuration: 1.1.1.1, 8.8.8.8.")
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")
        }

        vpnInterface = builder.establish()
            ?: throw IllegalStateException("VPN permission was not available or the TUN interface could not be established.")
        Log.i(TAG, "TUN interface established with $VPN_ADDRESS/$VPN_PREFIX_LENGTH, default IPv4 route, and MTU $VPN_MTU.")
    }

    private fun failConnect(step: String, reason: String, error: Throwable?) {
        val sanitizedReason = sanitizeError(reason)
        val message = if (sanitizedReason.startsWith("Start failed:")) sanitizedReason else "Start failed: $sanitizedReason"
        if (error == null) {
            Log.e(TAG, "Failed($step): $message")
        } else {
            Log.e(TAG, "Failed($step): $message", error)
        }
        stopTunnelAndTun()
        stopForegroundSafely()
        showToast(message)
        setState(ServiceState.Failed, message, step)
        stopSelf()
    }

    private fun stopTunnelAndTun() {
        try {
            tunnelCoreManager.stop()
        } catch (error: Throwable) {
            Log.w(TAG, "Error stopping native runtime.", error)
        }

        val tunToClose = synchronized(stateLock) {
            vpnInterface.also { vpnInterface = null }
        }
        if (tunToClose != null) {
            try {
                tunToClose.close()
            } catch (error: IOException) {
                Log.w(TAG, "Error closing VPN interface.", error)
            }
        } else {
            Log.i(TAG, "VPN interface was already detached or closed.")
        }
    }

    private fun startForegroundSafely(state: ServiceState) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(state))
            Log.i(TAG, "Foreground notification shown for state=$state.")
        } catch (error: Throwable) {
            Log.e(TAG, "Could not start VPN foreground notification.", error)
            throw error
        }
    }

    private fun stopForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Error stopping foreground VPN notification.", error)
        }
    }

    private fun setState(state: ServiceState, failureReason: String? = null, failedStep: String? = null) {
        synchronized(stateLock) {
            serviceState = state
            broadcastStateLocked(state, failureReason, failedStep)
        }
    }

    private fun broadcastStateLocked(state: ServiceState, failureReason: String? = null, failedStep: String? = null) {
        val sanitizedReason = failureReason?.let { sanitizeError(it) }
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATE, state.wireName)
                .putExtra(EXTRA_STATUS_MESSAGE, sanitizedReason ?: state.wireName)
                .putExtra(EXTRA_FAILURE_REASON, sanitizedReason.orEmpty())
                .putExtra(EXTRA_FAILED_STEP, failedStep.orEmpty())
                .putExtra(EXTRA_CONNECTED, state == ServiceState.Connected)
        )
    }

    private fun sanitizeError(message: String): String = message
        .replace(Regex("[\r\n\t]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .take(MAX_ERROR_CHARS)

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildNotification(state: ServiceState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText("VPN state: ${state.wireName}")
            .setContentIntent(pendingIntent)
            .setOngoing(state == ServiceState.Connecting || state == ServiceState.Connected)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private enum class ServiceState(val wireName: String) {
        Connecting("Connecting"),
        Connected("Connected"),
        Failed("Failed"),
        Stopping("Stopping"),
        Disconnected("Disconnected")
    }

    companion object {
        const val ACTION_CONNECT = "com.example.androidvpnapp.action.CONNECT"
        const val ACTION_DISCONNECT = "com.example.androidvpnapp.action.DISCONNECT"
        const val ACTION_STATUS = "com.example.androidvpnapp.action.STATUS"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_STATE = "state"
        const val EXTRA_FAILURE_REASON = "failure_reason"
        const val EXTRA_FAILED_STEP = "failed_step"
        const val STEP_FOREGROUND_NOTIFICATION = "STEP_FOREGROUND_NOTIFICATION"
        const val STEP_TUN_ESTABLISH = "STEP_TUN_ESTABLISH"
        const val STEP_XRAY_CONFIG_GENERATE = "STEP_XRAY_CONFIG_GENERATE"
        const val STEP_XRAY_CONFIG_TEST = "STEP_XRAY_CONFIG_TEST"
        const val STEP_XRAY_CORE_START = "STEP_XRAY_CORE_START"
        const val STEP_TUN2SOCKS_START = "STEP_TUN2SOCKS_START"
        private const val CHANNEL_ID = "vpn_connection"
        private const val NOTIFICATION_ID = 1001
        private const val VPN_ADDRESS = "10.10.0.2"
        private const val VPN_PREFIX_LENGTH = 32
        private const val VPN_MTU = 1500
        private const val TAG = "MyVpnService"
        private const val TAG_PROFILE = "VpnProfile"
        private const val MAX_ERROR_CHARS = 900
    }
}
