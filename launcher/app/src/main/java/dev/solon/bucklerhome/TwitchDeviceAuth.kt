package dev.solon.bucklerhome

import android.content.Context
import org.json.JSONObject

data class DeviceCodeSession(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val intervalSeconds: Int,
    val expiresInSeconds: Int,
)

sealed class DevicePollResult {
    data class Success(
        val accessToken: String,
        val refreshToken: String?,
        val expiresIn: Long,
    ) : DevicePollResult()

    data object Pending : DevicePollResult()
    data object SlowDown : DevicePollResult()
    data class Failed(val message: String) : DevicePollResult()
}

object TwitchDeviceAuth {
    private const val DEVICE_URL = "https://id.twitch.tv/oauth2/device"
    private const val TOKEN_URL = "https://id.twitch.tv/oauth2/token"

    fun clientIdConfigured(): Boolean = BuildConfig.TWITCH_CLIENT_ID.isNotBlank()

    fun startDeviceCode(): Result<DeviceCodeSession> {
        val clientId = BuildConfig.TWITCH_CLIENT_ID
        if (clientId.isBlank()) {
            return Result.failure(IllegalStateException("Twitch Client ID not configured"))
        }
        val response = HttpJson.postForm(
            DEVICE_URL,
            mapOf(
                "client_id" to clientId,
                "scopes" to "",
            ),
        )
        if (response.code !in 200..299) {
            return Result.failure(IllegalStateException("Device code HTTP ${response.code}"))
        }
        val json = HttpJson.parseObject(response.body)
            ?: return Result.failure(IllegalStateException("Bad device JSON"))
        return Result.success(
            DeviceCodeSession(
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUri = json.getString("verification_uri"),
                verificationUriComplete = json.optString("verification_uri_complete").takeIf { it.isNotBlank() },
                intervalSeconds = json.optInt("interval", 5).coerceAtLeast(3),
                expiresInSeconds = json.optInt("expires_in", 1800),
            ),
        )
    }

    fun poll(session: DeviceCodeSession): DevicePollResult {
        val clientId = BuildConfig.TWITCH_CLIENT_ID
        val response = HttpJson.postForm(
            TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "device_code" to session.deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        val json = HttpJson.parseObject(response.body) ?: JSONObject()
        if (response.code in 200..299 && json.has("access_token")) {
            return DevicePollResult.Success(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() },
                expiresIn = json.optLong("expires_in", 14_400L),
            )
        }
        return when (json.optString("message").lowercase()) {
            "authorization_pending" -> DevicePollResult.Pending
            "slow_down" -> DevicePollResult.SlowDown
            "access_denied" -> DevicePollResult.Failed("Access denied")
            "expired_token" -> DevicePollResult.Failed("Code expired")
            else -> DevicePollResult.Failed(json.optString("message").ifBlank { "HTTP ${response.code}" })
        }
    }

    fun refresh(context: Context): Boolean {
        val refresh = CoverAccounts.igdbRefreshToken(context) ?: return false
        val clientId = BuildConfig.TWITCH_CLIENT_ID
        if (clientId.isBlank()) return false
        val response = HttpJson.postForm(
            TOKEN_URL,
            mapOf(
                "client_id" to clientId,
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
            ),
        )
        val json = HttpJson.parseObject(response.body) ?: return false
        val access = json.optString("access_token")
        if (access.isBlank()) return false
        CoverAccounts.setIgdbTokens(
            context,
            access = access,
            refresh = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: refresh,
            expiresInSeconds = json.optLong("expires_in", 14_400L),
        )
        return true
    }

    fun ensureAccessToken(context: Context): String? {
        val access = CoverAccounts.igdbAccessToken(context) ?: return null
        val expires = CoverAccounts.igdbExpiresAt(context)
        if (System.currentTimeMillis() < expires) return access
        return if (refresh(context)) CoverAccounts.igdbAccessToken(context) else null
    }
}
