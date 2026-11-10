package app.xingmu.remote.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class User(
    val id: Int,
    val username: String,
    val role: String,
    val nickname: String = "",
    val phone: String = "",
    val avatarUrl: String = "",
    val membershipTier: String = "free",
    val membershipName: String = "免费会员",
    val vipActive: Boolean = false,
    val vipExpiresAt: Long = 0,
    val vipTagStyle: String = "sky",
    val vipPlanName: String = "",
    val vipDeviceLimit: Int = 1,
    val vipBenefits: List<String> = emptyList(),
    val vipEntitlements: Map<String, Boolean> = emptyMap(),
    val vipActivePlans: List<String> = emptyList(),
    val entitlementCatalog: List<EntitlementCapability> = emptyList(),
)

data class EntitlementCapability(
    val key: String,
    val label: String,
    val description: String,
    val enabled: Boolean,
    val free: Boolean,
    val requirement: String,
)

internal fun JSONObject.toUser() = User(
    id = optInt("id"),
    username = optString("username"),
    role = optString("role"),
    nickname = optString("nickname"),
    phone = optString("phone"),
    avatarUrl = optString("avatar_url"),
    membershipTier = optString("membership_tier", if (optBoolean("vip_active")) "vip" else "free"),
    membershipName = optString("membership_name", optString("vip_plan_name", "免费会员")),
    vipActive = optBoolean("vip_active"),
    vipExpiresAt = optLong("vip_expires_at"),
    vipTagStyle = optString("vip_tag_style", "sky"),
    vipPlanName = optString("vip_plan_name"),
    vipDeviceLimit = optInt("vip_device_limit", 1),
    vipBenefits = optJSONArray("vip_benefits").toStringList(),
    vipEntitlements = optJSONObject("vip_entitlements").toBooleanMap(),
    vipActivePlans = optJSONArray("vip_active_plans").toStringList(),
    entitlementCatalog = optJSONArray("entitlement_catalog").toEntitlementCapabilities(),
)

fun User.hasEntitlement(key: String): Boolean {
    if (role == "admin" || vipEntitlements[key] == true) return true
    if (key !in vipEntitlements && key in setOf("p2p_access", "relay_access")) {
        return vipEntitlements["screen_view"] == true && vipEntitlements["remote_control"] == true
    }
    return false
}

data class P2PSession(
    val selectedMode: String,
    val sessionId: String,
    val rendezvousHost: String,
    val rendezvousPort: Int,
    val token: String,
    val channelKey: String,
    val expiresAt: Long,
    val lanStreamHost: String,
    val lanStreamPort: Int,
    val lanStreamToken: String,
)

data class VipPlan(
    val id: Int, val name: String, val days: Int, val priceCents: Int,
    val tagStyle: String, val description: String, val active: Boolean,
    val deviceLimit: Int, val benefits: List<String>,
    val entitlements: Map<String, Boolean>, val badgeText: String,
    val accentColor: String,
)

data class VipOrder(
    val id: Int, val orderNo: String, val planName: String, val days: Int,
    val amountCents: Int, val tagStyle: String, val status: String, val createdAt: Long,
    val paymentStatus: String = "unpaid", val paymentMethod: String = "",
    val paymentTarget: String = "", val paymentPayloadType: String = "",
)

data class VipStatus(
    val salesEnabled: Boolean,
    val paymentEnabled: Boolean,
    val paymentMethods: List<String>,
    val user: User,
)

data class LoginResult(
    val accessToken: String,
    val user: User,
)

data class TvDevice(
    val id: Int,
    val deviceId: String,
    val name: String,
    val status: String,
    val enabled: Boolean,
    val online: Boolean,
    val powerState: String,
    val missedHeartbeats: Int,
    val model: String,
    val appVersion: String,
    val lanIp: String,
    val natType: String,
    val currentActivity: String,
    val playingUrl: String,
    val positionMs: Long,
    val batteryWhitelist: Boolean,
    val androidApi: Int,
    val androidRelease: String,
    val controlMode: String,
    val captureMode: String,
    val accessibilityActive: Boolean,
    val rootAccess: String,
    val foregroundPackage: String,
    val memoryAvailableBytes: Long,
    val memoryTotalBytes: Long,
    val storageAvailableBytes: Long,
    val storageTotalBytes: Long,
    val agentMetadata: Map<String, String>,
) {
    val controllable: Boolean
        get() = online && enabled && status == "approved"
}

data class DeviceEvent(
    val id: Long,
    val type: String,
    val data: JSONObject,
    val createdAt: Long,
)

data class InstalledTvApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val system: Boolean,
    val enabled: Boolean,
    val launchable: Boolean,
    val iconDataUri: String,
    val collectedAt: Long,
)

data class ClientRelease(
    val platform: String,
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val mandatory: Boolean,
    val filename: String,
    val size: Long,
    val sha256: String,
    val url: String,
)

data class ReleaseCheck(
    val updateAvailable: Boolean,
    val release: ClientRelease?,
)

data class CommandReceipt(
    val id: Long,
    val status: String,
    val type: String,
    val result: JSONObject?,
)

data class UploadedApk(
    val filename: String,
    val sha256: String,
    val url: String,
    val size: Long,
    val packageName: String,
    val minSdk: Int,
)

data class HostedPlayerSession(
    val playerPath: String,
    val playerUrl: String,
    val expiresAt: Long,
    val transcodeAvailable: Boolean,
    val note: String,
)

data class SharedMedia(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val size: Long,
    val url: String = "",
)

internal fun JSONObject.toTvDevice(): TvDevice {
    val metadata = optJSONObject("metadata") ?: JSONObject()
    val allMetadata = buildMap {
        val keys = metadata.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = metadata.opt(key)
            put(key, if (value == null || value == JSONObject.NULL) "" else value.toString())
        }
    }
    return TvDevice(
    id = optInt("id"),
    deviceId = optString("device_id"),
    name = optString("name", optString("device_id")),
    status = optString("status"),
    enabled = optBoolean("enabled"),
    online = optBoolean("online"),
    powerState = optString("power_state", if (optBoolean("online")) "online" else "reconnecting"),
    missedHeartbeats = optInt("missed_heartbeats"),
    model = optString("model"),
    appVersion = optString("app_version"),
    lanIp = optString("lan_ip"),
    natType = optString("nat_type"),
    currentActivity = optString("current_activity"),
    playingUrl = optString("playing_url"),
    positionMs = optLong("position_ms"),
    batteryWhitelist = optBoolean("battery_whitelist"),
    androidApi = metadata.optInt("android_api"),
    androidRelease = metadata.optString("android_release"),
    controlMode = metadata.optString("control_mode", "unavailable"),
    captureMode = metadata.optString("capture_mode", "unavailable"),
    accessibilityActive = metadata.optBoolean("accessibility_active"),
    rootAccess = metadata.optString("root_access", "unavailable"),
    foregroundPackage = metadata.optString("foreground_package"),
    memoryAvailableBytes = metadata.optLong("memory_available_bytes", -1L),
    memoryTotalBytes = metadata.optLong("memory_total_bytes", -1L),
    storageAvailableBytes = metadata.optLong("storage_available_bytes", -1L),
    storageTotalBytes = metadata.optLong("storage_total_bytes", -1L),
    agentMetadata = allMetadata,
    )
}

internal fun JSONArray.toTvDevices(): List<TvDevice> = buildList {
    for (index in 0 until length()) {
        optJSONObject(index)?.let { add(it.toTvDevice()) }
    }
}

internal fun JSONArray.toInstalledTvApps(): List<InstalledTvApp> = buildList {
    for (index in 0 until length()) {
        val item = optJSONObject(index) ?: continue
        val packageName = item.optString("package_name").trim()
        if (packageName.isEmpty()) continue
        add(
            InstalledTvApp(
                packageName = packageName,
                label = item.optString("label", packageName).ifBlank { packageName },
                versionName = item.optString("version_name"),
                versionCode = item.optLong("version_code"),
                system = item.optBoolean("system"),
                enabled = item.optBoolean("enabled", true),
                launchable = item.optBoolean("launchable"),
                iconDataUri = item.optString("icon_data_uri")
                    .takeIf { it.startsWith("data:image/") && it.length <= 100_000 }
                    .orEmpty(),
                collectedAt = item.optLong("collected_at"),
            ),
        )
    }
}

internal fun JSONObject.toReleaseCheck(): ReleaseCheck {
    val value = optJSONObject("release")
    val release = value?.let {
        ClientRelease(
            platform = it.optString("platform"),
            versionCode = it.optInt("version_code"),
            versionName = it.optString("version_name"),
            releaseNotes = it.optString("release_notes"),
            mandatory = it.optBoolean("mandatory"),
            filename = it.optString("filename"),
            size = it.optLong("size"),
            sha256 = it.optString("sha256"),
            url = it.optString("url"),
        )
    }
    return ReleaseCheck(optBoolean("update_available") && release != null, release)
}

internal fun JSONObject.toVipOrder() = VipOrder(
    id = optInt("id"), orderNo = optString("order_no"), planName = optString("plan_name"),
    days = optInt("days"), amountCents = optInt("amount_cents"),
    tagStyle = optString("tag_style"), status = optString("status"),
    createdAt = optLong("created_at"), paymentStatus = optString("payment_status", "unpaid"),
    paymentMethod = optString("payment_method"), paymentTarget = optString("payment_target"),
    paymentPayloadType = optString("payment_payload_type"),
)

private fun JSONArray?.toStringList(): List<String> = buildList {
    val array = this@toStringList ?: return@buildList
    for (index in 0 until array.length()) array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
}

private fun JSONArray?.toEntitlementCapabilities(): List<EntitlementCapability> = buildList {
    val array = this@toEntitlementCapabilities ?: return@buildList
    for (index in 0 until array.length()) {
        val item = array.optJSONObject(index) ?: continue
        add(
            EntitlementCapability(
                key = item.optString("key"),
                label = item.optString("label"),
                description = item.optString("description"),
                enabled = item.optBoolean("enabled"),
                free = item.optBoolean("free"),
                requirement = item.optString("requirement"),
            ),
        )
    }
}

private fun JSONObject?.toBooleanMap(): Map<String, Boolean> = buildMap {
    val value = this@toBooleanMap ?: return@buildMap
    val keys = value.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        put(key, value.optBoolean(key))
    }
}

internal fun JSONObject.toCommandReceipt(): CommandReceipt = CommandReceipt(
    id = optLong("id"),
    status = optString("status"),
    type = optString("type"),
    result = optJSONObject("result"),
)
