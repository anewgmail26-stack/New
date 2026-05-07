package com.example.androidvpnapp

import android.content.Context
import android.util.Log
import org.json.JSONArray

class ConfigStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveV2RayConfig(jsonConfig: String) {
        prefs.edit().putString(KEY_V2RAY_CONFIG, jsonConfig.trim()).commit()
    }

    fun loadV2RayConfig(): String = prefs.getString(KEY_V2RAY_CONFIG, "").orEmpty()

    fun saveSelectedServerId(serverId: String) {
        prefs.edit().putString(KEY_SELECTED_SERVER_ID, serverId).commit()
    }

    fun loadSelectedServerId(): String = prefs.getString(
        KEY_SELECTED_SERVER_ID,
        SampleTunnelCatalog.servers.first().id
    ).orEmpty()

    fun saveSelectedPayloadId(payloadId: String) {
        prefs.edit().putString(KEY_SELECTED_PAYLOAD_ID, payloadId).commit()
    }

    fun loadSelectedPayloadId(): String = prefs.getString(
        KEY_SELECTED_PAYLOAD_ID,
        SampleTunnelCatalog.defaultPayloadTweak.id
    ).orEmpty()

    fun saveDnsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DNS_ENABLED, enabled).commit()
    }

    fun loadDnsEnabled(): Boolean = prefs.getBoolean(KEY_DNS_ENABLED, false)

    fun loadServers(): List<TunnelServer> {
        val savedServers = loadSavedServers()
        if (savedServers.isNotEmpty()) return savedServers
        return loadAssetServers().ifEmpty { SampleTunnelCatalog.servers }
    }

    private fun loadSavedServers(): List<TunnelServer> {
        val json = prefs.getString(KEY_SERVER_JSON, "").orEmpty()
        if (json.isBlank()) return emptyList()
        return try {
            parseServers(json, "saved server JSON")
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to parse saved server JSON.", error)
            emptyList()
        }
    }

    private fun loadAssetServers(): List<TunnelServer> = try {
        val json = context.assets.open(SERVERS_ASSET).bufferedReader().use { it.readText() }
        parseServers(json, SERVERS_ASSET)
    } catch (error: Throwable) {
        Log.e(TAG, "Failed to load asset server list from $SERVERS_ASSET.", error)
        emptyList()
    }

    private fun parseServers(json: String, source: String): List<TunnelServer> {
        val array = JSONArray(json)
        return (0 until array.length())
            .map { index -> TunnelServer.fromJson(array.getJSONObject(index)) }
            .onEach { server ->
                Log.i(TAG, "Config parsed from $source: id=${server.id}, host=${server.host}, port=${server.port}, network=${server.type}, security=${server.security}, sni=${server.sni}, path=${server.wsPath}, allowInsecure=${server.allowInsecure}.")
            }
            .filter { it.enabled }
            .sortedWith(compareBy<TunnelServer> { it.sortOrder }.thenBy { it.name })
    }

    fun saveServerJson(json: String) {
        JSONArray(json)
        prefs.edit().putString(KEY_SERVER_JSON, json).commit()
    }

    fun loadSelectedProfile(): TunnelProfile? {
        val allServers = loadServers()
        val server = allServers.firstOrNull { it.id == loadSelectedServerId() } ?: allServers.firstOrNull() ?: return null
        return TunnelProfile(server, SampleTunnelCatalog.defaultPayloadTweak, loadDnsEnabled())
    }

    companion object {
        private const val PREFS_NAME = "vpn_config_store"
        private const val KEY_V2RAY_CONFIG = "v2ray_json_config"
        private const val KEY_SELECTED_SERVER_ID = "selected_server_id"
        private const val KEY_SELECTED_PAYLOAD_ID = "selected_payload_id"
        private const val KEY_DNS_ENABLED = "dns_enabled"
        private const val KEY_SERVER_JSON = "server_json"
        private const val SERVERS_ASSET = "servers.json"
        private const val TAG = "ConfigStore"
    }
}
