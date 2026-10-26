package dev.solon.bucklerhome

import android.content.Context

enum class CoverProvider {
    STEAMGRIDDB,
    IGDB,
}

object CoverAccounts {
    private const val KEY_SGDB = "cover_account_sgdb_token"
    private const val KEY_IGDB_ACCESS = "cover_account_igdb_access"
    private const val KEY_IGDB_REFRESH = "cover_account_igdb_refresh"
    private const val KEY_IGDB_EXPIRES = "cover_account_igdb_expires_at"

    fun isLinked(context: Context, provider: CoverProvider): Boolean = when (provider) {
        CoverProvider.STEAMGRIDDB -> !sgdbToken(context).isNullOrBlank()
        CoverProvider.IGDB -> !igdbAccessToken(context).isNullOrBlank()
    }

    fun sgdbToken(context: Context): String? =
        SecurePrefs.get(context).getString(KEY_SGDB, null)?.takeIf { it.isNotBlank() }

    fun setSgdbToken(context: Context, token: String?) {
        SecurePrefs.get(context).edit().apply {
            if (token.isNullOrBlank()) remove(KEY_SGDB) else putString(KEY_SGDB, token.trim())
        }.apply()
    }

    fun disconnect(context: Context, provider: CoverProvider) {
        when (provider) {
            CoverProvider.STEAMGRIDDB -> setSgdbToken(context, null)
            CoverProvider.IGDB -> clearIgdb(context)
        }
    }

    fun igdbAccessToken(context: Context): String? =
        SecurePrefs.get(context).getString(KEY_IGDB_ACCESS, null)?.takeIf { it.isNotBlank() }

    fun igdbRefreshToken(context: Context): String? =
        SecurePrefs.get(context).getString(KEY_IGDB_REFRESH, null)?.takeIf { it.isNotBlank() }

    fun igdbExpiresAt(context: Context): Long =
        SecurePrefs.get(context).getLong(KEY_IGDB_EXPIRES, 0L)

    fun setIgdbTokens(
        context: Context,
        access: String,
        refresh: String?,
        expiresInSeconds: Long,
    ) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds.coerceAtLeast(60) - 60) * 1000L
        SecurePrefs.get(context).edit()
            .putString(KEY_IGDB_ACCESS, access)
            .putString(KEY_IGDB_REFRESH, refresh)
            .putLong(KEY_IGDB_EXPIRES, expiresAt)
            .apply()
    }

    fun clearIgdb(context: Context) {
        SecurePrefs.get(context).edit()
            .remove(KEY_IGDB_ACCESS)
            .remove(KEY_IGDB_REFRESH)
            .remove(KEY_IGDB_EXPIRES)
            .apply()
    }

    fun statusLabel(context: Context, provider: CoverProvider): String =
        if (isLinked(context, provider)) "Linked" else "Not linked"
}
