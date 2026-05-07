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
    val flow: String = ""
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
            hostHeader = json.optString("hostHeader", ""),
            flow = json.optString("flow", "")
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
                                        .put("flow", server.flow)
                                )
                            )
                    )
                )
            )
            .put("streamSettings", streamSettings)

        return JSONObject()
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
                    .put("payloadTweak", payloadTweak.toJson())
                    .put("dnsEnabled", dnsEnabled)
            )
            .toString(2)
    }

    fun toInternalJson(): String = toXrayJson()
}

object SampleTunnelCatalog {
    val servers = listOf(
        TunnelServer(
            id = "green-edge",
            name = "Green Edge Server",
            host = "edge.example.net",
            port = 443,
            type = "tcp",
            security = "tls",
            sni = "edge.example.net",
            encryption = "none",
            allowInsecure = false,
            remark = "Sample Green Edge",
            uuid = "00000000-0000-4000-8000-000000000001"
        ),
        TunnelServer(
            id = "cloud-tunnel",
            name = "Cloud Tunnel Server",
            host = "cloud.example.net",
            port = 443,
            type = "ws",
            security = "tls",
            sni = "cloud.example.net",
            encryption = "none",
            allowInsecure = false,
            remark = "Sample Cloud Tunnel",
            uuid = "00000000-0000-4000-8000-000000000002"
        ),
        TunnelServer(
            id = "lab-direct",
            name = "Lab Direct Server",
            host = "198.51.100.10",
            port = 80,
            type = "tcp",
            security = "none",
            sni = "",
            encryption = "none",
            allowInsecure = false,
            remark = "Sample Lab Direct",
            uuid = "00000000-0000-4000-8000-000000000003"
        )
    )

    val payloadTweaks = listOf(
        PayloadTweak(
            id = "http-default",
            name = "Default HTTP Tweak",
            mode = "HTTP",
            payload = "GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf][crlf]"
        ),
        PayloadTweak(
            id = "tls-sni",
            name = "TLS SNI Tweak",
            mode = "TLS",
            payload = "SNI=[sni];ALPN=h2,http/1.1"
        ),
        PayloadTweak(
            id = "direct",
            name = "Direct Mode",
            mode = "DIRECT",
            payload = ""
        )
    )
}
