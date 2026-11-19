package io.github.lnrdo.mytidal.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.lnrdo.mytidal.core.ClientIdentity
import io.github.lnrdo.mytidal.core.Session
import io.github.lnrdo.mytidal.core.SessionStore
import io.github.lnrdo.mytidal.core.PkceLogin
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSessionStore(context: Context) : SessionStore {
    private val preferences = context.getSharedPreferences("private_session", Context.MODE_PRIVATE)
    private val alias = "opentune.session.v1"
    private var sessionLoaded = false
    private var cachedSession: Session? = null

    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    @Synchronized private fun writeEncrypted(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val result = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(name, Base64.encodeToString(result, Base64.NO_WRAP)).commit()) {
            "The account session could not be saved."
        }
    }

    @Synchronized private fun readEncrypted(name: String): String? {
        val stored = preferences.getString(name, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            }
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            preferences.edit().remove(name).apply()
            null
        }
    }

    @Synchronized override fun read(): Session? {
        if (!sessionLoaded) {
            cachedSession = readEncrypted("session")?.let { text ->
                try {
                    val value = JSONObject(text)
                    Session(value.getString("accessToken"), value.getString("refreshToken"), value.getLong("expiresAt"),
                        value.optString("userId"), value.optString("country", "US"))
                } catch (_: Exception) { null }
            }
            sessionLoaded = true
        }
        return cachedSession
    }

    @Synchronized override fun write(session: Session) {
        writeEncrypted("session", JSONObject().apply {
            put("accessToken", session.accessToken); put("refreshToken", session.refreshToken)
            put("expiresAt", session.expiresAt); put("userId", session.userId); put("country", session.country)
        }.toString())
        cachedSession = session; sessionLoaded = true
    }

    @Synchronized override fun clear() { preferences.edit().remove("session").apply(); cachedSession = null; sessionLoaded = true }

    fun savePkce(login: PkceLogin) = writeEncrypted("pending_pkce", JSONObject().apply {
        put("url", login.authorizationUrl); put("verifier", login.verifier); put("state", login.state)
        put("unique", login.uniqueKey); put("expiresAt", login.expiresAt)
    }.toString())

    fun pendingPkce(): PkceLogin? = readEncrypted("pending_pkce")?.let { text -> runCatching {
        val json = JSONObject(text)
        PkceLogin(json.getString("url"), json.getString("verifier"), json.getString("state"), json.getString("unique"), json.getLong("expiresAt"))
            .takeIf { it.expiresAt > System.currentTimeMillis() }
    }.getOrNull() }

    fun clearPkce() { preferences.edit().remove("pending_pkce").apply() }

    /** The ListenBrainz user token. It is stored encrypted like the TIDAL session. */
    fun scrobbleToken(): String? = readEncrypted("scrobble_token")?.takeIf(String::isNotBlank)
    fun setScrobbleToken(token: String) {
        if (token.isBlank()) preferences.edit().remove("scrobble_token").apply() else writeEncrypted("scrobble_token", token.trim())
    }

    fun identity(): ClientIdentity {
        fun decode(value: String) = String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8)
        return ClientIdentity(
            decode(decode("TmtKRVUxSmtjRXM=") + decode("NWFIRkZRbFJuVlE9PQ==")),
            decode(decode("ZUdWMVVHMVpOMjVpY0ZvNVNVbGlURUZqVVQ=") + decode("a3pjMmhyWVRGV1RtaGxWVUZ4VGpaSlkzTjZhbFJIT0QwPQ==")),
        )
    }
}
