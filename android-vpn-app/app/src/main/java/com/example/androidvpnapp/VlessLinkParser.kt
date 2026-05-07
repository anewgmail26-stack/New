package com.example.androidvpnapp

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object VlessLinkParser {
    fun parseToServer(link: String): Result<TunnelServer> {
        val trimmed = link.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("VLESS link cannot be empty."))
        }
        if (!trimmed.startsWith("vless://", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("VLESS link must start with vless://."))
        }

        return try {
            val uri = Uri.parse(trimmed)
            val uuid = uri.userInfo.orEmpty()
            if (uuid.isBlank()) {
                return Result.failure(IllegalArgumentException("VLESS link is missing the UUID before @."))
            }
            try {
                UUID.fromString(uuid)
            } catch (error: Exception) {
                return Result.failure(IllegalArgumentException("VLESS UUID is invalid: ${error.message}"))
            }

            val host = uri.host.orEmpty()
            if (host.isBlank()) {
                return Result.failure(IllegalArgumentException("VLESS link is missing a host."))
            }

            val port = uri.port
            if (port <= 0) {
                return Result.failure(IllegalArgumentException("VLESS link is missing a valid port."))
            }

            val type = uri.getQueryParameter("type")?.ifBlank { null } ?: "tcp"
            val security = uri.getQueryParameter("security")?.ifBlank { null } ?: "none"
            val sni = uri.getQueryParameter("sni")?.ifBlank { null }
                ?: uri.getQueryParameter("serverName")?.ifBlank { null }
                ?: ""
            val encryption = uri.getQueryParameter("encryption")?.ifBlank { null } ?: "none"
            val allowInsecure = parseBoolean(uri.getQueryParameter("allowInsecure"))
            val remark = uri.fragment?.let(Uri::decode)?.ifBlank { null } ?: host

            Result.success(
                TunnelServer(
                    id = "vless-${host.hashCode()}-$port",
                    name = remark,
                    host = host,
                    port = port,
                    type = type,
                    security = security,
                    sni = sni,
                    encryption = encryption,
                    allowInsecure = allowInsecure,
                    remark = remark,
                    uuid = uuid
                )
            )
        } catch (error: Exception) {
            Result.failure(IllegalArgumentException("Could not parse VLESS link: ${error.message}", error))
        }
    }

    fun toInternalJson(link: String): Result<String> = parseToServer(link).map { server ->
        val outbound = JSONObject()
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(
                        JSONObject()
                            .put("address", server.host)
                            .put("port", server.port)
                            .put(
                                "users",
                                JSONArray().put(
                                    JSONObject()
                                        .put("id", server.uuid)
                                        .put("encryption", server.encryption)
                                        .put("flow", "")
                                )
                            )
                    )
                )
            )
            .put(
                "streamSettings",
                JSONObject()
                    .put("network", server.type)
                    .put("security", server.security)
                    .put(
                        "tlsSettings",
                        JSONObject()
                            .put("serverName", server.sni)
                            .put("allowInsecure", server.allowInsecure)
                    )
            )

        JSONObject()
            .put("app", "My Tunnel Lite")
            .put("importType", "vless")
            .put("remarks", server.remark)
            .put("outbounds", JSONArray().put(outbound))
            .toString(2)
    }

    private fun parseBoolean(value: String?): Boolean = when (value?.lowercase()) {
        "1", "true", "yes" -> true
        else -> false
    }
}
