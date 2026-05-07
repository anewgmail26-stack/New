package com.example.androidvpnapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException

class MyVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private lateinit var v2RayManager: V2RayManager

    override fun onCreate() {
        super.onCreate()
        v2RayManager = V2RayManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> connect()
            ACTION_DISCONNECT -> disconnect()
            else -> Log.w(TAG, "Ignoring unknown VPN service action: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun connect() {
        val jsonConfig = ConfigStore(applicationContext).loadV2RayConfig()
        if (jsonConfig.isBlank()) {
            Log.w(TAG, "Cannot start VPN: V2Ray config is empty.")
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val result = v2RayManager.start(jsonConfig)
        if (result.isFailure) {
            Log.e(TAG, "Cannot start V2Ray manager.", result.exceptionOrNull())
            stopForegroundCompat()
            stopSelf()
            return
        }

        try {
            // This creates a real Android VPN TUN interface. Traffic forwarding requires the native core
            // integration described in V2RayManager; until then the tunnel is a safe placeholder.
            vpnInterface = Builder()
                .setSession(getString(R.string.app_name))
                .addAddress("10.10.0.2", 32)
                .addDnsServer("1.1.1.1")
                .addRoute("0.0.0.0", 0)
                .establish()

            if (vpnInterface == null) {
                Log.e(TAG, "VpnService.Builder.establish() returned null. User approval may be missing.")
                disconnect()
            } else {
                Log.i(TAG, "VPN interface established.")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to establish VPN interface.", error)
            disconnect()
        }
    }

    private fun disconnect() {
        v2RayManager.stop()
        try {
            vpnInterface?.close()
        } catch (error: IOException) {
            Log.w(TAG, "Error closing VPN interface.", error)
        } finally {
            vpnInterface = null
        }
        stopForegroundCompat()
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
        private const val CHANNEL_ID = "vpn_connection"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "MyVpnService"
    }
}
