package com.salar.tv

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound encrypted storage for Xtream credentials. */
object XtreamCredentialVault {
    private const val SCHEME = "salarxtream"
    private const val PREFS = "salar_xtream_vault"
    private const val KEY_ALIAS = "salar_tv_xtream_aes_v1"
    private const val IV_BYTES = 12

    fun buildStoredUrl(sourceId: String, streamId: String): String {
        require(sourceId.isNotBlank() && streamId.isNotBlank())
        return Uri.Builder().scheme(SCHEME).authority(sourceId).appendPath(streamId).build().toString()
    }

    fun isStoredUrl(value: String): Boolean = runCatching {
        Uri.parse(value).scheme.equals(SCHEME, ignoreCase = true)
    }.getOrDefault(false)

    fun isSupportedStoredUrl(value: String): Boolean {
        if (value.startsWith("https://", true) || value.startsWith("http://", true)) return true
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        return uri.scheme.equals(SCHEME, true) && !uri.host.isNullOrBlank() && !uri.lastPathSegment.isNullOrBlank()
    }

    fun store(context: Context, sourceId: String, server: String, username: String, password: String) {
        require(server.startsWith("http://", true) || server.startsWith("https://", true))
        require(username.isNotBlank() && password.isNotBlank())
        val payload = JSONObject()
            .put("server", server.trimEnd('/'))
            .put("username", username)
            .put("password", password)
            .toString().toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val packed = cipher.iv + cipher.doFinal(payload)
        val encoded = Base64.encodeToString(packed, Base64.NO_WRAP)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(sourceId, encoded).apply()
    }

    fun resolve(context: Context, storedUrl: String): String {
        if (!isStoredUrl(storedUrl)) return storedUrl
        val uri = Uri.parse(storedUrl)
        val sourceId = uri.host ?: error("شناسه منبع Xtream معتبر نیست.")
        val streamId = uri.lastPathSegment ?: error("شناسه شبکه Xtream موجود نیست.")
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(sourceId, null)
            ?: error("اطلاعات ورود این سرور روی این دستگاه ذخیره نشده است.")
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > IV_BYTES) { "داده رمزنگاری‌شده منبع ناقص است." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
            GCMParameterSpec(128, packed.copyOfRange(0, IV_BYTES)))
        val credentials = JSONObject(String(cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size)), StandardCharsets.UTF_8))
        val server = credentials.getString("server").trimEnd('/')
        val username = URLEncoder.encode(credentials.getString("username"), "UTF-8")
        val password = URLEncoder.encode(credentials.getString("password"), "UTF-8")
        return "$server/live/$username/$password/$streamId.ts"
    }

    /** Migrates old /live/user/pass/id.ts URLs; never keeps plaintext if encryption fails. */
    fun migrateLegacyUrl(context: Context, value: String): String {
        if (isStoredUrl(value)) return value
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return value
        val parts = uri.pathSegments
        if (parts.size < 4 || !parts[0].equals("live", true)) return value
        val finalPart = parts.last()
        val streamId = finalPart.substringBeforeLast('.', finalPart)
        if (streamId.isBlank() || !(finalPart.endsWith(".ts", true) || finalPart.endsWith(".m3u8", true))) return value
        val server = Uri.Builder().scheme(uri.scheme).encodedAuthority(uri.encodedAuthority).build().toString()
        val sourceId = UUID.randomUUID().toString()
        return runCatching {
            store(context, sourceId, server, parts[1], parts[2])
            buildStoredUrl(sourceId, streamId)
        }.getOrElse { "salarxtream-error://credentials-migration-failed" }
    }

    fun migrateScheduleJson(context: Context, raw: String): String {
        val schedules = JSONArray(raw)
        for (index in 0 until schedules.length()) {
            val item = schedules.optJSONObject(index) ?: continue
            val oldUrl = item.optString("url", "")
            val safeUrl = migrateLegacyUrl(context, oldUrl)
            if (safeUrl != oldUrl) item.put("url", safeUrl)
        }
        return schedules.toString()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
}
