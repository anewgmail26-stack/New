package com.example.androidvpnapp

import org.json.JSONArray
import org.json.JSONObject

data class TunnelServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val type: String,
    val security: String,
    val sni: String,
    val encryption: String,
    val allowInsecure: Boolean,
    val remark: String,
    val uuid: String,
    val wsPath: String = "/",
    val hostHeader: String = "",
    val flow: String = "",
    val enabled: Boolean = true,
    val premiumLabel: String = "",
    val sortOrder: Int = Int.MAX_VALUE
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("host", host)
        .put("port", port)
        .put("type", type)
        .put("security", security)
        .put("sni", sni)
        .put("encryption", encryption)
        .put("allowInsecure", allowInsecure)
        .put("remark", remark)
        .put("uuid", uuid)
        .put("wsPath", wsPath)
        .put("hostHeader", hostHeader)
        .put("flow", flow)
        .put("enabled", enabled)
        .put("premiumLabel", premiumLabel)
        .put("sortOrder", sortOrder)

    companion object {
        fun fromJson(json: JSONObject): TunnelServer = TunnelServer(
            id = json.optString("id", json.optString("name", json.optString("host"))),
            name = json.optString("name", json.optString("remark", "VLESS Server")),
            host = json.getString("host"),
            port = json.optInt("port", 443),
            type = json.optString("type", json.optString("network", "tcp")),
            security = json.optString("security", "tls"),
            sni = json.optString("sni", json.optString("serverName", json.optString("host"))),
            encryption = json.optString("encryption", "none"),
            allowInsecure = json.optBoolean("allowInsecure", false),
            remark = json.optString("remark", json.optString("name", "VLESS Server")),
            uuid = json.getString("uuid"),
            wsPath = json.optString("wsPath", json.optString("path", "/")),
            hostHeader = json.optString("hostHeader", json.optString("wsHost", json.optString("headerHost", ""))),
            flow = json.optString("flow", ""),
            enabled = json.optBoolean("enabled", true),
            premiumLabel = json.optString("premiumLabel", ""),
            sortOrder = if (json.has("sortOrder")) json.optInt("sortOrder", Int.MAX_VALUE) else Int.MAX_VALUE
        )
    }
}

data class PayloadTweak(
    val id: String,
    val name: String,
    val mode: String,
    val payload: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("mode", mode)
        .put("payload", payload)
}

data class TunnelProfile(
    val server: TunnelServer,
    val payloadTweak: PayloadTweak,
    val dnsEnabled: Boolean
) {
    fun toXrayJson(): String {
        val streamSettings = JSONObject()
            .put("network", server.type)
            .put("security", server.security)

        if (server.security == "tls") {
            streamSettings.put(
                "tlsSettings",
                JSONObject()
                    .put("serverName", server.sni)
                    .put("allowInsecure", server.allowInsecure)
            )
        }

        if (server.type == "ws") {
            streamSettings.put(
                "wsSettings",
                JSONObject()
                    .put("path", server.wsPath.ifBlank { "/" })
                    .put("headers", JSONObject().put("Host", server.hostHeader.ifBlank { server.sni.ifBlank { server.host } }))
            )
        }

        val outbound = JSONObject()
            .put("tag", "proxy")
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
                                        .apply {
                                            if (server.flow.isNotBlank()) put("flow", server.flow)
                                        }
                                )
                            )
                    )
                )
            )
            .put("streamSettings", streamSettings)

        val config = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put(
                "dns",
                JSONObject().put(
                    "servers",
                    if (dnsEnabled) JSONArray().put("1.1.1.1").put("8.8.8.8") else JSONArray().put("localhost")
                )
            )
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("tag", "tun-in")
                        .put("listen", "127.0.0.1")
                        .put("port", 10808)
                        .put("protocol", "socks")
                        .put("settings", JSONObject().put("udp", true))
                )
            )
            .put("outbounds", JSONArray().put(outbound).put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
            .put(
                "routing",
                JSONObject()
                    .put("domainStrategy", "AsIs")
                    .put("rules", JSONArray())
            )
            .put(
                "myTunnelLite",
                JSONObject()
                    .put("profileName", server.remark.ifBlank { server.name })
                    .put("mode", "v2ray")
                    .put("dnsEnabled", dnsEnabled)
            )

        validateXrayJson(config)
        return config.toString(2)
    }

    fun toInternalJson(): String = toXrayJson()

    companion object {
        fun validateXrayJson(configJson: String): Result<Unit> = runCatching {
            validateXrayJson(JSONObject(configJson))
        }

        private fun validateXrayJson(config: JSONObject) {
            val inbound = config.getJSONArray("inbounds").getJSONObject(0)
            require(inbound.getString("protocol") == "socks") { "Generated Xray inbound must be SOCKS." }
            require(inbound.getString("listen") == "127.0.0.1") { "Generated Xray SOCKS inbound must listen on 127.0.0.1." }
            require(inbound.getInt("port") == 10808) { "Generated Xray SOCKS inbound must listen on port 10808." }

            val outbound = config.getJSONArray("outbounds").getJSONObject(0)
            require(outbound.getString("protocol") == "vless") { "Generated Xray outbound must use VLESS." }
            val vnext = outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
            require(vnext.getString("address") == "shar1.knlvpn.com") { "Generated VLESS address must be shar1.knlvpn.com." }
            require(vnext.getInt("port") == 80) { "Generated VLESS port must be 80." }
            val user = vnext.getJSONArray("users").getJSONObject(0)
            require(user.getString("id") == "48990253-ed95-4aac-9ad3-ad4457e50b14") { "Generated VLESS UUID does not match the selected server." }
            require(user.getString("encryption") == "none") { "Generated VLESS encryption must be none." }

            val stream = outbound.getJSONObject("streamSettings")
            require(stream.getString("network") == "ws") { "Generated VLESS network must be ws." }
            require(stream.getString("security") == "none") { "Generated VLESS security must be none." }
            val ws = stream.getJSONObject("wsSettings")
            require(ws.getString("path") == "/") { "Generated VLESS WS path must be /." }
            require(ws.getJSONObject("headers").getString("Host") == "telegram.org") { "Generated VLESS WS Host header must be telegram.org." }
        }
    }
}

object SampleTunnelCatalog {
    val servers = listOf(
        TunnelServer(
            id = "vpn",
            name = "V2Ray 80 Server",
            host = "shar1.knlvpn.com",
            port = 80,
            type = "ws",
            security = "none",
            sni = "",
            encryption = "none",
            allowInsecure = false,
            remark = "V2Ray port 80",
            uuid = "48990253-ed95-4aac-9ad3-ad4457e50b14",
            wsPath = "/",
            hostHeader = "telegram.org"
        )
    )

    val defaultPayloadTweak = PayloadTweak(
        id = "v2ray-direct",
        name = "V2Ray",
        mode = "V2RAY",
        payload = ""
    )

    val payloadTweaks = listOf(defaultPayloadTweak)
}
