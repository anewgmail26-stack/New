package com.example.androidvpnapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var tunnelCoreManager: TunnelCoreManager
    private var stopRequested = false

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN service created.")
        tunnelCoreManager = TunnelCoreManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "VPN service start command received: action=${intent?.action}, flags=$flags, startId=$startId.")
        when (intent?.action) {
            ACTION_CONNECT -> safeConnect()
            ACTION_DISCONNECT -> disconnect("Disconnected", stopService = true)
            else -> Log.w(TAG, "Ignoring unknown VPN service action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "VPN service is being destroyed; releasing VPN and native resources.")
        disconnect("Disconnected", stopService = false)
        super.onDestroy()
    }

    private fun safeConnect() {
        stopRequested = false
        try {
            Log.i(TAG, "Starting VPN foreground notification before doing any blocking work.")
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "Foreground notification started.")
            connect()
        } catch (error: UnsatisfiedLinkError) {
            handleStartFailure("native library could not be loaded: ${error.message}", error)
        } catch (error: NoClassDefFoundError) {
            handleStartFailure("native runtime class is missing: ${error.message}", error)
        } catch (error: IllegalStateException) {
            handleStartFailure(error.message ?: "illegal VPN service state", error)
        } catch (error: IOException) {
            handleStartFailure("I/O error while starting VPN: ${error.message}", error)
        } catch (error: Throwable) {
            handleStartFailure(error.message ?: error::class.java.simpleName, error)
        }
    }

    private fun connect() {
        val configStore = ConfigStore(applicationContext)
        val profile = configStore.loadSelectedProfile()
        Log.i(TAG, "VPN connect flow started; selectedProfilePresent=${profile != null}.")

        try {
            ensureTunInterface(configStore)
            broadcastStatus("Connecting", true)

            if (profile == null) {
                handleStartFailure(TunnelCoreManager.CoreStatus.CONFIG_MISSING.label, null)
                return
            }

            Log.i(
                TAG_PROFILE,
                "Config parsed: id=${profile.server.id}, protocol=vless, host=${profile.server.host}, port=${profile.server.port}, network=${profile.server.type}, security=${profile.server.security}, sni=${profile.server.sni}, wsPath=${profile.server.wsPath}, allowInsecure=${profile.server.allowInsecure}."
            )
            Log.i(
                TAG_PROFILE,
                "VLESS settings parsed: uuid=${profile.server.uuid}, encryption=${profile.server.encryption}, flow=${profile.server.flow.ifBlank { "<none>" }}, wsHost=${profile.server.hostHeader.ifBlank { profile.server.sni.ifBlank { profile.server.host } }}."
            )

            val coreStatus = tunnelCoreManager.getStatus(profile)
            Log.i(TAG, "Native runtime preflight status: $coreStatus.")
            if (coreStatus == TunnelCoreManager.CoreStatus.TUN2SOCKS_NOT_INSTALLED ||
                coreStatus == TunnelCoreManager.CoreStatus.GOJNI_NOT_INSTALLED ||
                coreStatus == TunnelCoreManager.CoreStatus.START_API_NOT_WIRED
            ) {
                handleStartFailure(coreStatus.label, null)
                return
            }

            val tun = vpnInterface
            if (tun == null) {
                handleStartFailure("Android VPN TUN interface was not established.", null)
                return
            }

            Log.i(TAG, "Starting native V2Ray runtime and tun2socks.")
            val result = tunnelCoreManager.start(profile, tun) { socket -> protect(socket) }
            if (result.isFailure) {
                handleStartFailure(result.exceptionOrNull()?.message ?: "native runtime did not start", result.exceptionOrNull())
                return
            }

            Log.i(TAG, "Native runtime and TUN routing started.")
            broadcastStatus("Connected", true)
        } catch (error: UnsatisfiedLinkError) {
            handleStartFailure("native library could not be loaded: ${error.message}", error)
        } catch (error: NoClassDefFoundError) {
            handleStartFailure("native runtime class is missing: ${error.message}", error)
        } catch (error: IllegalStateException) {
            handleStartFailure(error.message ?: "illegal VPN service state", error)
        } catch (error: IOException) {
            handleStartFailure("I/O error while starting VPN: ${error.message}", error)
        } catch (error: Throwable) {
            handleStartFailure(error.message ?: error::class.java.simpleName, error)
        }
    }

    private fun ensureTunInterface(configStore: ConfigStore) {
        if (vpnInterface != null) {
            Log.i(TAG, "Reusing existing Android VPN TUN interface while reconnecting.")
            return
        }

        Log.i(TAG, "Starting Android VPN TUN interface setup.")
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .addAddress("10.10.0.2", 32)
            .addRoute("0.0.0.0", 0)

        if (configStore.loadDnsEnabled()) {
            Log.i(TAG, "Adding DNS servers to TUN configuration: 1.1.1.1, 8.8.8.8.")
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")
        }

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            throw IllegalStateException("VPN permission was not available or the TUN interface could not be established.")
        }
        Log.i(TAG, "TUN interface established; Android VPN icon should remain visible until the user disconnects.")
    }

    private fun handleStartFailure(reason: String, error: Throwable?) {
        val message = if (reason.startsWith("Start failed:")) reason else "Start failed: $reason"
        if (error == null) {
            Log.e(TAG, message)
        } else {
            Log.e(TAG, message, error)
        }
        showToast(message)
        Log.e(TAG, "Keeping VPN service and TUN interface alive after start failure so the VPN icon does not disappear immediately. User must press STOP to disconnect.")
        broadcastStatus(message, true)
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun broadcastStatus(message: String, connected: Boolean) {
        sendBroadcast(
            Intent(ACTION_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_STATUS_MESSAGE, message)
                .putExtra(EXTRA_CONNECTED, connected)
        )
    }

    private fun disconnect(statusMessage: String = "Disconnected", stopService: Boolean = true) {
        if (stopRequested && stopService) {
            Log.i(TAG, "Disconnect already requested; ignoring duplicate stop request.")
            return
        }
        stopRequested = stopService
        Log.i(TAG, "Disconnecting VPN service; statusMessage=$statusMessage, stopService=$stopService.")
        try {
            tunnelCoreManager.stop()
        } catch (error: UnsatisfiedLinkError) {
            Log.w(TAG, "Error stopping native runtime: unsatisfied link.", error)
        } catch (error: NoClassDefFoundError) {
            Log.w(TAG, "Error stopping native runtime: missing class.", error)
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Error stopping native runtime: illegal state.", error)
        } catch (error: IOException) {
            Log.w(TAG, "Error stopping native runtime: I/O error.", error)
        } catch (error: Throwable) {
            Log.w(TAG, "Error stopping native runtime.", error)
        }

        if (vpnInterface != null) {
            try {
                vpnInterface?.close()
            } catch (error: IOException) {
                Log.w(TAG, "Error closing VPN interface.", error)
            } finally {
                vpnInterface = null
            }
        } else {
            Log.i(TAG, "VPN interface was already detached or closed.")
        }
        broadcastStatus(statusMessage, false)
        try {
            stopForegroundCompat()
        } catch (error: Throwable) {
            Log.w(TAG, "Error stopping foreground VPN notification.", error)
        }
        if (stopService) {
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
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
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
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

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.example.androidvpnapp.action.CONNECT"
        const val ACTION_DISCONNECT = "com.example.androidvpnapp.action.DISCONNECT"
        const val ACTION_STATUS = "com.example.androidvpnapp.action.STATUS"
        const val EXTRA_STATUS_MESSAGE = "status_message"
        const val EXTRA_CONNECTED = "connected"
        private const val CHANNEL_ID = "vpn_connection"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "MyVpnService"
        private const val TAG_PROFILE = "VpnProfile"
    }
}
