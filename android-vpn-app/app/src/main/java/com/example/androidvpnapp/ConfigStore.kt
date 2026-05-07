package com.example.androidvpnapp

import android.content.Context
import org.json.JSONArray

class ConfigStore(private val context: Context) {
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

    fun loadServers(): List<TunnelServer> = loadAssetServers().ifEmpty { SampleTunnelCatalog.servers }

    private fun loadAssetServers(): List<TunnelServer> = runCatching {
        val json = prefs.all[KEY_SERVER_JSON] as? String
            ?: context.assets.open(SERVERS_ASSET).bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        (0 until array.length())
            .map { index -> TunnelServer.fromJson(array.getJSONObject(index)) }
            .filter { it.enabled }
            .sortedWith(compareBy<TunnelServer> { it.sortOrder }.thenBy { it.name })
    }.getOrDefault(emptyList())

    fun saveServerJson(json: String) {
        JSONArray(json)
        prefs.edit().putString(KEY_SERVER_JSON, json).apply()
    }

    fun loadSelectedProfile(): TunnelProfile? {
        val allServers = loadServers()
        val server = allServers.firstOrNull { it.id == loadSelectedServerId() } ?: allServers.firstOrNull() ?: return null
        val payload = SampleTunnelCatalog.payloadTweaks.firstOrNull { it.id == loadSelectedPayloadId() }
            ?: SampleTunnelCatalog.payloadTweaks.firstOrNull()
            ?: return null
        return TunnelProfile(server, payload, loadDnsEnabled())
    }

    companion object {
        private const val PREFS_NAME = "vpn_config_store"
        private const val KEY_V2RAY_CONFIG = "v2ray_json_config"
        private const val KEY_SELECTED_SERVER_ID = "selected_server_id"
        private const val KEY_SELECTED_PAYLOAD_ID = "selected_payload_id"
        private const val KEY_DNS_ENABLED = "dns_enabled"
        private const val KEY_SERVER_JSON = "server_json"
        private const val SERVERS_ASSET = "servers.json"
    }
}
