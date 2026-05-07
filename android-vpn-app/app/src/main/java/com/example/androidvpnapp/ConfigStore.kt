package com.example.androidvpnapp

import android.content.Context

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveV2RayConfig(jsonConfig: String) {
        prefs.edit().putString(KEY_V2RAY_CONFIG, jsonConfig.trim()).apply()
    }

    fun loadV2RayConfig(): String = prefs.getString(KEY_V2RAY_CONFIG, "").orEmpty()

    companion object {
        private const val PREFS_NAME = "vpn_config_store"
        private const val KEY_V2RAY_CONFIG = "v2ray_json_config"
    }
}
