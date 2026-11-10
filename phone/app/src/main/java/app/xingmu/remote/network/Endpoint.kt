package app.xingmu.remote.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object Endpoint {
    fun normalize(raw: String): String {
        val value = raw.trim().trimEnd('/')
        val parsed = value.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("请输入正确的 HTTP 或 HTTPS 后端地址")
        require(parsed.scheme == "http" || parsed.scheme == "https") {
            "后端地址只支持 HTTP 或 HTTPS"
        }
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "后端地址不能包含账号或密码"
        }
        require(parsed.query == null && parsed.fragment == null) {
            "后端地址不能包含参数或片段"
        }
        return parsed.newBuilder().query(null).fragment(null).build().toString().trimEnd('/')
    }

    fun controlWebSocket(baseUrl: String, deviceId: String): String {
        val base = normalize(baseUrl).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("后端地址无效")
        val httpUrl = base.newBuilder()
            .addPathSegments("ws/control")
            .addPathSegment(deviceId)
            .build()
            .toString()
        return if (base.isHttps) httpUrl.replaceFirst("https://", "wss://")
        else httpUrl.replaceFirst("http://", "ws://")
    }

    fun pairingClaim(baseUrl: String, code: String): String {
        require(code.isNotBlank()) { "电视绑定码不能为空" }
        val base = normalize(baseUrl).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("后端地址无效")
        return base.newBuilder()
            .addPathSegments("api/pairing")
            .addPathSegment(code)
            .addPathSegment("claim")
            .build()
            .toString()
    }
}
