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
    val uuid: String
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
    fun toInternalJson(): String {
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

        return JSONObject()
            .put("app", "My Tunnel Lite")
            .put("profileName", server.remark.ifBlank { server.name })
            .put("dnsEnabled", dnsEnabled)
            .put("payloadTweak", payloadTweak.toJson())
            .put("outbounds", JSONArray().put(outbound))
            .put("remarks", server.remark)
            .toString(2)
    }
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
