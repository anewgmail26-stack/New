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

    fun saveImportedVlessServer(server: TunnelServer) {
        prefs.edit()
            .putString(KEY_IMPORTED_SERVER_ID, server.id)
            .putString(KEY_IMPORTED_SERVER_NAME, server.name)
            .putString(KEY_IMPORTED_SERVER_HOST, server.host)
            .putInt(KEY_IMPORTED_SERVER_PORT, server.port)
            .putString(KEY_IMPORTED_SERVER_TYPE, server.type)
            .putString(KEY_IMPORTED_SERVER_SECURITY, server.security)
            .putString(KEY_IMPORTED_SERVER_SNI, server.sni)
            .putString(KEY_IMPORTED_SERVER_ENCRYPTION, server.encryption)
            .putBoolean(KEY_IMPORTED_SERVER_ALLOW_INSECURE, server.allowInsecure)
            .putString(KEY_IMPORTED_SERVER_REMARK, server.remark)
            .putString(KEY_IMPORTED_SERVER_UUID, server.uuid)
            .putString(KEY_SELECTED_SERVER_ID, server.id)
            .apply()
    }

    fun loadImportedVlessServer(): TunnelServer? {
        val id = prefs.getString(KEY_IMPORTED_SERVER_ID, null) ?: return null
        val host = prefs.getString(KEY_IMPORTED_SERVER_HOST, null) ?: return null
        val uuid = prefs.getString(KEY_IMPORTED_SERVER_UUID, null) ?: return null
        return TunnelServer(
            id = id,
            name = prefs.getString(KEY_IMPORTED_SERVER_NAME, host).orEmpty(),
            host = host,
            port = prefs.getInt(KEY_IMPORTED_SERVER_PORT, 443),
            type = prefs.getString(KEY_IMPORTED_SERVER_TYPE, "tcp").orEmpty(),
            security = prefs.getString(KEY_IMPORTED_SERVER_SECURITY, "none").orEmpty(),
            sni = prefs.getString(KEY_IMPORTED_SERVER_SNI, "").orEmpty(),
            encryption = prefs.getString(KEY_IMPORTED_SERVER_ENCRYPTION, "none").orEmpty(),
            allowInsecure = prefs.getBoolean(KEY_IMPORTED_SERVER_ALLOW_INSECURE, false),
            remark = prefs.getString(KEY_IMPORTED_SERVER_REMARK, host).orEmpty(),
            uuid = uuid
        )
    }

    fun loadServers(): List<TunnelServer> = listOfNotNull(loadImportedVlessServer()) + SampleTunnelCatalog.servers

    companion object {
        private const val PREFS_NAME = "vpn_config_store"
        private const val KEY_V2RAY_CONFIG = "v2ray_json_config"
        private const val KEY_SELECTED_SERVER_ID = "selected_server_id"
        private const val KEY_SELECTED_PAYLOAD_ID = "selected_payload_id"
        private const val KEY_DNS_ENABLED = "dns_enabled"
        private const val KEY_IMPORTED_SERVER_ID = "imported_server_id"
        private const val KEY_IMPORTED_SERVER_NAME = "imported_server_name"
        private const val KEY_IMPORTED_SERVER_HOST = "imported_server_host"
        private const val KEY_IMPORTED_SERVER_PORT = "imported_server_port"
        private const val KEY_IMPORTED_SERVER_TYPE = "imported_server_type"
        private const val KEY_IMPORTED_SERVER_SECURITY = "imported_server_security"
        private const val KEY_IMPORTED_SERVER_SNI = "imported_server_sni"
        private const val KEY_IMPORTED_SERVER_ENCRYPTION = "imported_server_encryption"
        private const val KEY_IMPORTED_SERVER_ALLOW_INSECURE = "imported_server_allow_insecure"
        private const val KEY_IMPORTED_SERVER_REMARK = "imported_server_remark"
        private const val KEY_IMPORTED_SERVER_UUID = "imported_server_uuid"
    }
}
