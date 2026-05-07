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

    override fun onCreate() {
        super.onCreate()
        tunnelCoreManager = TunnelCoreManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> safeConnect()
            ACTION_DISCONNECT -> disconnect()
            else -> Log.w(TAG, "Ignoring unknown VPN service action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun safeConnect() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
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
        if (profile == null) {
            handleStartFailure(TunnelCoreManager.CoreStatus.CONFIG_MISSING.label, null)
            return
        }

        val coreStatus = tunnelCoreManager.getStatus(profile)
        if (coreStatus == TunnelCoreManager.CoreStatus.CORE_NOT_INSTALLED ||
            coreStatus == TunnelCoreManager.CoreStatus.TUN2SOCKS_NOT_INSTALLED ||
            coreStatus == TunnelCoreManager.CoreStatus.GOJNI_NOT_INSTALLED ||
            coreStatus == TunnelCoreManager.CoreStatus.START_API_NOT_WIRED
        ) {
            handleStartFailure(coreStatus.label, null)
            return
        }

        try {
            Log.i(TAG_PROFILE, "Starting selected server/profile: id=${profile.server.id}, name=${profile.server.name}, host=${profile.server.host}:${profile.server.port}, type=${profile.server.type}, security=${profile.server.security}.")
            // This creates a real Android VPN TUN interface. Traffic forwarding requires CoreBridge
            // to hand this file descriptor to a real tun2socks/native routing adapter.
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .addAddress("10.10.0.2", 32)
                .addRoute("0.0.0.0", 0)

            if (configStore.loadDnsEnabled()) {
                builder.addDnsServer("1.1.1.1")
                builder.addDnsServer("8.8.8.8")
            }

            vpnInterface = builder.establish()

            if (vpnInterface == null) {
                handleStartFailure("VPN permission was not available or the TUN interface could not be established.", null)
                return
            }

            val result = tunnelCoreManager.start(profile, vpnInterface) { socket -> protect(socket) }
            if (result.isFailure) {
                handleStartFailure(result.exceptionOrNull()?.message ?: "native core did not start", result.exceptionOrNull())
                return
            }

            Log.i(TAG, "VPN interface established and native core manager is running.")
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

    private fun handleStartFailure(reason: String, error: Throwable?) {
        val message = if (reason.startsWith("Start failed:")) reason else "Start failed: $reason"
        if (error == null) {
            Log.e(TAG, message)
        } else {
            Log.e(TAG, message, error)
        }
        showToast(message)
        disconnect(message)
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

    private fun disconnect(statusMessage: String = "Disconnected") {
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

        try {
            vpnInterface?.close()
        } catch (error: IOException) {
            Log.w(TAG, "Error closing VPN interface.", error)
        } finally {
            vpnInterface = null
        }
        broadcastStatus(statusMessage, false)
        try {
            stopForegroundCompat()
        } catch (error: Throwable) {
            Log.w(TAG, "Error stopping foreground VPN notification.", error)
        }
        stopSelf()
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
