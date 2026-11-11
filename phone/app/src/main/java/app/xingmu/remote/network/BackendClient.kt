package app.xingmu.remote.network

import android.content.Context
import android.os.Build
import android.net.Uri
import android.provider.OpenableColumns
import app.xingmu.remote.data.CommandReceipt
import app.xingmu.remote.data.ClientRelease
import app.xingmu.remote.data.DeviceEvent
import app.xingmu.remote.data.LoginResult
import app.xingmu.remote.data.HostedPlayerSession
import app.xingmu.remote.data.UploadedApk
import app.xingmu.remote.data.User
import app.xingmu.remote.data.P2PSession
import app.xingmu.remote.data.VipPlan
import app.xingmu.remote.data.VipOrder
import app.xingmu.remote.data.VipStatus
import app.xingmu.remote.data.toCommandReceipt
import app.xingmu.remote.data.toInstalledTvApps
import app.xingmu.remote.data.toReleaseCheck
import app.xingmu.remote.data.toTvDevice
import app.xingmu.remote.data.toTvDevices
import app.xingmu.remote.data.toUser
import app.xingmu.remote.data.toVipOrder
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class BackendClient(private val context: Context) {
    @Volatile
    private var unauthorizedListener: (() -> Unit)? = null

    internal val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .pingInterval(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun login(baseUrl: String, username: String, password: String): LoginResult {
        val root = Endpoint.normalize(baseUrl)
        val body = JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .toString()
            .toRequestBody(JSON)
        val json = executeJson(
            Request.Builder().url("$root/api/auth/login").post(body).build(),
            token = null,
            retryCount = 1,
        ) as JSONObject
        return LoginResult(
            accessToken = json.getString("access_token"),
            user = json.getJSONObject("user").toUser(),
        )
    }

    suspend fun register(
        baseUrl: String,
        username: String,
        password: String,
        nickname: String,
        phone: String,
    ): LoginResult {
        val root = Endpoint.normalize(baseUrl)
        val body = JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .put("nickname", nickname.trim())
            .put("phone", phone.trim())
            .toString()
            .toRequestBody(JSON)
        val json = executeJson(
            Request.Builder().url("$root/api/auth/register").post(body).build(),
            token = null,
        ) as JSONObject
        return LoginResult(
            accessToken = json.getString("access_token"),
            user = json.getJSONObject("user").toUser(),
        )
    }

    suspend fun claimPairing(baseUrl: String, token: String, code: String) =
        (executeJson(
            Request.Builder().url(Endpoint.pairingClaim(baseUrl, code))
                  .post(ByteArray(0).toRequestBody(null))
                  .build(),
            token,
        ) as JSONObject).toTvDevice()

    suspend fun getCurrentUser(baseUrl: String, token: String): User {
        val json = executeJson(request(baseUrl, "/api/auth/me"), token) as JSONObject
        return json.toUser()
    }

    suspend fun updateProfile(
        baseUrl: String,
        token: String,
        nickname: String,
        phone: String,
    ): User {
        val body = JSONObject()
            .put("nickname", nickname.trim())
            .put("phone", phone.trim())
            .toString()
            .toRequestBody(JSON)
        return (executeJson(
            request(baseUrl, "/api/auth/profile").newBuilder().patch(body).build(), token,
        ) as JSONObject).toUser()
    }

    suspend fun changePassword(
        baseUrl: String,
        token: String,
        oldPassword: String,
        newPassword: String,
        confirmPassword: String,
    ) {
        val body = JSONObject()
            .put("old_password", oldPassword)
            .put("new_password", newPassword)
            .put("new_password_confirm", confirmPassword)
            .toString()
            .toRequestBody(JSON)
        executeJson(request(baseUrl, "/api/auth/password").newBuilder().post(body).build(), token)
    }

    suspend fun uploadAvatar(baseUrl: String, token: String, uri: Uri): User {
        val resolver = context.contentResolver
        var name = "avatar.jpg"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: name
                    size = if (cursor.isNull(1)) -1 else cursor.getLong(1)
                }
            }
        val mime = (resolver.getType(uri) ?: "image/jpeg").toMediaType()
        val streamBody = object : RequestBody() {
            override fun contentType() = mime
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                    }
                } ?: throw IOException("无法读取头像")
            }
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, streamBody).build()
        return (executeJson(
            request(baseUrl, "/api/auth/avatar").newBuilder().post(body).build(), token,
        ) as JSONObject).toUser()
    }

    suspend fun listDevices(baseUrl: String, token: String) =
        (executeJson(request(baseUrl, "/api/devices"), token) as JSONArray).toTvDevices()

    suspend fun listApprovals(baseUrl: String, token: String) =
        (executeJson(request(baseUrl, "/api/device-approvals"), token) as JSONArray).toTvDevices()

    suspend fun reviewApproval(
        baseUrl: String,
        token: String,
        deviceId: String,
        approve: Boolean,
        ownerUserId: Int? = null,
    ) = (executeJson(
        request(baseUrl, "/api/device-approvals/$deviceId/${if (approve) "approve" else "reject"}")
            .newBuilder().post(
            (ownerUserId?.let { JSONObject().put("owner_user_id", it) } ?: JSONObject()).toString().toRequestBody(JSON),
        ).build(),
        token,
    ) as JSONObject).toTvDevice()

    suspend fun getDevice(baseUrl: String, token: String, deviceId: String) =
        (executeJson(request(baseUrl, "/api/device/$deviceId"), token) as JSONObject).toTvDevice()

    suspend fun deleteDevice(baseUrl: String, token: String, deviceId: String) {
        executeJson(
            request(baseUrl, "/api/device/$deviceId").newBuilder().delete().build(),
            token,
        )
    }

    suspend fun unbindDevice(baseUrl: String, token: String, deviceId: String) {
        executeJson(
            request(baseUrl, "/api/device/$deviceId/binding").newBuilder().delete().build(), token,
        )
    }

    suspend fun listEvents(baseUrl: String, token: String, deviceId: String): List<DeviceEvent> {
        val array = executeJson(request(baseUrl, "/api/device/$deviceId/events"), token) as JSONArray
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    DeviceEvent(
                        id = item.optLong("id"),
                        type = item.optString("type"),
                        data = item.optJSONObject("data") ?: JSONObject(),
                        createdAt = item.optLong("created_at"),
                    ),
                )
            }
        }
    }

    suspend fun listInstalledApps(baseUrl: String, token: String, deviceId: String) =
        (executeJson(request(baseUrl, "/api/device/$deviceId/apps"), token) as JSONArray)
            .toInstalledTvApps()

    suspend fun createP2PSession(
        baseUrl: String,
        token: String,
        deviceId: String,
        mode: String = "auto",
    ): P2PSession {
        val json = executeJson(
            request(baseUrl, "/api/device/$deviceId/p2p/session").newBuilder()
                .post(JSONObject().put("mode", mode).toString().toRequestBody(JSON)).build(), token,
            retryCount = 2,
        ) as JSONObject
        val transport = json.optJSONObject("transport") ?: JSONObject()
        return P2PSession(
            selectedMode = json.optString("selected_mode"), sessionId = json.optString("session_id"),
            rendezvousHost = transport.optString("rendezvous_host"), rendezvousPort = transport.optInt("rendezvous_port"),
              token = transport.optString("token"), channelKey = transport.optString("channel_key"),
              expiresAt = transport.optLong("expires_at"),
              lanStreamHost = transport.optString("lan_stream_host"),
              lanStreamPort = transport.optInt("lan_stream_port", 8080),
              lanStreamToken = transport.optString("lan_stream_token"),
          )
    }

    suspend fun listVipPlans(baseUrl: String, token: String): List<VipPlan> {
        val array = executeJson(request(baseUrl, "/api/vip/plans"), token) as JSONArray
        return buildList { for (index in 0 until array.length()) array.optJSONObject(index)?.let { item ->
            val benefits = buildList { val values = item.optJSONArray("benefits") ?: JSONArray(); for (i in 0 until values.length()) values.optString(i).takeIf(String::isNotBlank)?.let(::add) }
            val entitlements = buildMap { val values = item.optJSONObject("entitlements") ?: JSONObject(); val keys = values.keys(); while (keys.hasNext()) { val key = keys.next(); put(key, values.optBoolean(key)) } }
            add(VipPlan(item.optInt("id"), item.optString("name"), item.optInt("days"), item.optInt("price_cents"), item.optString("tag_style"), item.optString("description"), item.optBoolean("active"), item.optInt("device_limit", 1), benefits, entitlements, item.optString("badge_text"), item.optString("accent_color", "#2F80ED")))
        } }
    }

    suspend fun listVipOrders(baseUrl: String, token: String): List<VipOrder> {
        val array = executeJson(request(baseUrl, "/api/vip/orders"), token) as JSONArray
        return buildList { for (index in 0 until array.length()) array.optJSONObject(index)?.let { add(it.toVipOrder()) } }
    }

    suspend fun getVipStatus(baseUrl: String, token: String): VipStatus {
        val json = executeJson(request(baseUrl, "/api/vip/status"), token) as JSONObject
        val methods = buildList {
            val values = json.optJSONArray("payment_methods") ?: JSONArray()
            for (index in 0 until values.length()) values.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
        return VipStatus(
            salesEnabled = json.optBoolean("sales_enabled"),
            paymentEnabled = json.optBoolean("payment_enabled"),
            paymentMethods = methods,
            user = json.getJSONObject("user").toUser(),
        )
    }

    suspend fun createVipPayment(
        baseUrl: String, token: String, planId: Int, paymentMethod: String,
    ): VipOrder = (executeJson(
        request(baseUrl, "/api/vip/payments").newBuilder().post(
            JSONObject().put("plan_id", planId).put("payment_method", paymentMethod)
                .toString().toRequestBody(JSON),
        ).build(), token,
    ) as JSONObject).toVipOrder()

    suspend fun getVipPayment(baseUrl: String, token: String, orderNo: String): VipOrder =
        (executeJson(request(baseUrl, "/api/vip/payments/$orderNo"), token) as JSONObject).toVipOrder()

    suspend fun createVipOrder(baseUrl: String, token: String, planId: Int) {
        executeJson(request(baseUrl, "/api/vip/orders").newBuilder().post(
            JSONObject().put("plan_id", planId).toString().toRequestBody(JSON),
        ).build(), token)
    }

    suspend fun redeemVipCode(baseUrl: String, token: String, code: String): User {
        val json = executeJson(request(baseUrl, "/api/vip/redeem").newBuilder().post(
            JSONObject().put("code", code.trim()).toString().toRequestBody(JSON),
        ).build(), token) as JSONObject
        return json.getJSONObject("user").toUser()
    }

    suspend fun latestMobileRelease(baseUrl: String, currentVersionCode: Int) =
        (executeJson(
            request(baseUrl, "/api/releases/latest/mobile?current_version_code=$currentVersionCode"),
            token = null,
        ) as JSONObject).toReleaseCheck()

    suspend fun downloadRelease(baseUrl: String, release: ClientRelease): File {
        val normalizedUrl = release.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: throw IOException("更新地址无效")
        val request = Request.Builder().url(normalizedUrl).get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("更新包下载失败"))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        var partial: File? = null
                        try {
                            val body = response.body ?: throw IOException("更新包响应为空")
                            if (!response.isSuccessful || body.contentLength() > MAX_APK_BYTES) {
                                throw IOException("更新包响应异常（${response.code}）")
                            }
                            val root = context.getExternalFilesDir("Download") ?: context.cacheDir
                            partial = File(root, "mobile-update-${release.versionCode}.apk.part")
                            val target = File(root, "mobile-update-${release.versionCode}.apk")
                            val digest = MessageDigest.getInstance("SHA-256")
                            var total = 0L
                            body.byteStream().use { input ->
                                FileOutputStream(partial).use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        if (total > MAX_APK_BYTES) throw IOException("更新包过大")
                                        digest.update(buffer, 0, count)
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            val actual = digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
                            if (!actual.equals(release.sha256, ignoreCase = true)) throw IOException("更新包校验失败")
                            if (target.exists() && !target.delete()) throw IOException("无法替换旧更新包")
                            if (!partial.renameTo(target)) throw IOException("无法保存更新包")
                            if (continuation.isActive) continuation.resume(target)
                        } catch (error: Exception) {
                            partial?.delete()
                            if (continuation.isActive) continuation.resumeWithException(
                                if (error is IOException) error else IOException("更新包处理失败"),
                            )
                        }
                    }
                }
            })
        }
    }

    suspend fun listCommands(baseUrl: String, token: String, deviceId: String): List<CommandReceipt> {
        val array = executeJson(request(baseUrl, "/api/device/$deviceId/commands"), token) as JSONArray
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(it.toCommandReceipt()) }
            }
        }
    }

    suspend fun sendCommand(
        baseUrl: String,
        token: String,
        deviceId: String,
        type: String,
        payload: JSONObject,
    ): CommandReceipt {
        val body = JSONObject().put("type", type).put("payload", payload)
            .toString().toRequestBody(JSON)
        val result = executeJson(
            request(baseUrl, "/api/device/$deviceId/commands").newBuilder().post(body).build(),
            token,
        ) as JSONObject
        return result.toCommandReceipt()
    }

    suspend fun createHostedPlayerSession(
        baseUrl: String,
        token: String,
        url: String,
        mediaType: String,
    ): HostedPlayerSession {
        val body = JSONObject()
            .put("url", url)
            .put("media_type", mediaType)
            .put("title", "星投屏")
            .toString()
            .toRequestBody(JSON)
        val result = executeJson(
            request(baseUrl, "/api/player/sessions").newBuilder().post(body).build(),
            token,
        ) as JSONObject
        return HostedPlayerSession(
            playerPath = result.getString("player_path"),
            playerUrl = result.getString("player_url"),
            expiresAt = result.optLong("expires_at"),
            transcodeAvailable = result.optBoolean("transcode_available"),
            note = result.optString("note"),
        )
    }

    suspend fun uploadApk(baseUrl: String, token: String, uri: Uri): UploadedApk {
        val resolver = context.contentResolver
        var name = "receiver.apk"
        var reportedSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)?.takeIf { it.endsWith(".apk", true) } ?: name
                    reportedSize = if (cursor.isNull(1)) -1 else cursor.getLong(1)
                }
            }
        if (reportedSize > MAX_APK_BYTES) throw IOException("安装包不能超过 200 MB")

        val localCopy = File.createTempFile("starlight-upload-", ".apk", context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(localCopy).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_APK_BYTES) throw IOException("安装包不能超过 200 MB")
                        output.write(buffer, 0, read)
                    }
                }
            } ?: throw IOException("无法读取所选安装包")
            if (localCopy.length() <= 0L) throw IOException("所选安装包为空")

            val archive = context.packageManager.getPackageArchiveInfo(localCopy.absolutePath, 0)
            val packageName = archive?.packageName.orEmpty()
            val minSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                archive?.applicationInfo?.minSdkVersion ?: 0
            } else {
                0
            }

            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file", name, localCopy.asRequestBody(APK))
                .build()
            val json = executeJson(
                request(baseUrl, "/api/upload/apk").newBuilder().post(body).build(),
                token,
            ) as JSONObject
            return UploadedApk(
                filename = json.getString("filename"),
                sha256 = json.getString("sha256"),
                url = json.getString("url"),
                size = json.getLong("size"),
                packageName = packageName,
                minSdk = minSdk,
            )
        } finally {
            localCopy.delete()
        }
    }

    fun controlSocket(
        baseUrl: String,
        token: String,
        deviceId: String,
        listener: ControlSocket.Listener,
    ) = ControlSocket(httpClient, Endpoint.controlWebSocket(baseUrl, deviceId), token, listener)

    fun setUnauthorizedListener(listener: (() -> Unit)?) {
        unauthorizedListener = listener
    }

    private fun request(baseUrl: String, path: String): Request = Request.Builder()
        .url(Endpoint.normalize(baseUrl) + path)
        .get()
        .build()

    private suspend fun executeJson(
        request: Request,
        token: String?,
        retryCount: Int = if (request.method == "GET" || request.method == "HEAD") 2 else 0,
    ): Any {
        var attempt = 0
        while (true) {
            try {
                return executeJsonOnce(request, token)
            } catch (error: IOException) {
                if (error is ApiException || attempt >= retryCount) throw error
                delay(350L shl attempt.coerceAtMost(2))
                attempt++
            }
        }
    }

    private suspend fun executeJsonOnce(request: Request, token: String?): Any {
        val authenticated = request.newBuilder().apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(authenticated)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(IOException("无法连接后端：${error.localizedMessage}"))
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            if (response.code == 401 && !token.isNullOrBlank()) {
                                unauthorizedListener?.invoke()
                            }
                            val detail = runCatching { JSONObject(raw).optString("detail") }.getOrNull()
                                ?.takeIf { it.isNotBlank() }
                                ?: "请求失败（${response.code}）"
                            if (continuation.isActive) continuation.resumeWithException(ApiException(response.code, detail))
                            return
                        }
                        try {
                            val parsed: Any = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw)
                            if (continuation.isActive) continuation.resume(parsed)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(IOException("后端返回了无法识别的数据"))
                        }
                    }
                }
            })
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val APK = "application/vnd.android.package-archive".toMediaType()
        private const val MAX_APK_BYTES = 200L * 1024L * 1024L
    }
}

class ApiException(val statusCode: Int, override val message: String) : IOException(message)
