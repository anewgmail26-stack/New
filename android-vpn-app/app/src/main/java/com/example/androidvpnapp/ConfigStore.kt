package com.example.androidvpnapp

import android.content.Context

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveV2RayConfig(jsonConfig: String) {
        prefs.edit().putString(KEY_V2RAY_CONFIG, jsonConfig.trim()).apply()
    }

    fun loadV2RayConfig(): String = prefs.getString(KEY_V2RAY_CONFIG, "").orEmpty()

    fun saveSelectedServerId(serverId: String) {
        prefs.edit().putString(KEY_SELECTED_SERVER_ID, serverId).apply()
    }

    fun loadSelectedServerId(): String = prefs.getString(
        KEY_SELECTED_SERVER_ID,
        SampleTunnelCatalog.servers.first().id
    ).orEmpty()

    fun saveSelectedPayloadId(payloadId: String) {
        prefs.edit().putString(KEY_SELECTED_PAYLOAD_ID, payloadId).apply()
    }

    fun loadSelectedPayloadId(): String = prefs.getString(
        KEY_SELECTED_PAYLOAD_ID,
        SampleTunnelCatalog.payloadTweaks.first().id
    ).orEmpty()

    fun saveDnsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DNS_ENABLED, enabled).apply()
    }

    fun loadDnsEnabled(): Boolean = prefs.getBoolean(KEY_DNS_ENABLED, false)

    fun loadServers(): List<TunnelServer> = SampleTunnelCatalog.servers

    fun loadSelectedProfile(): TunnelProfile? {
        val server = loadServers().firstOrNull { it.id == loadSelectedServerId() } ?: return null
        val payload = SampleTunnelCatalog.payloadTweaks.firstOrNull { it.id == loadSelectedPayloadId() } ?: return null
        return TunnelProfile(server, payload, loadDnsEnabled())
    }

    companion object {
        private const val PREFS_NAME = "vpn_config_store"
        private const val KEY_V2RAY_CONFIG = "v2ray_json_config"
        private const val KEY_SELECTED_SERVER_ID = "selected_server_id"
        private const val KEY_SELECTED_PAYLOAD_ID = "selected_payload_id"
        private const val KEY_DNS_ENABLED = "dns_enabled"
    }
}
