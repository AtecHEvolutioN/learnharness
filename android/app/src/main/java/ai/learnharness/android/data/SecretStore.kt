package ai.learnharness.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ai.learnharness.android.domain.*
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecretStore(context: Context, namespace: String = "provider") {
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val alias = "learnharness-$namespace-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun settings() = ProviderSettings(prefs.getString("url", null) ?: ProviderSettings().baseUrl, prefs.getString("model", "").orEmpty())
    fun hasKey() = prefs.contains("secret")
    fun readKey(): String {
        val encrypted = prefs.getString("secret", null) ?: return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }
    fun save(settings: ProviderSettings, newKey: String) {
        require(validateEndpoint(settings.baseUrl)) { "Use an HTTPS API base URL without a query or credentials" }
        require(settings.model.isNotBlank()) { "Enter a model ID" }
        val previous = this.settings()
        val edit = prefs.edit().putString("url", settings.baseUrl.trimEnd('/')).putString("model", settings.model.trim())
        if (previous.baseUrl.trimEnd('/') != settings.baseUrl.trimEnd('/')) edit.remove("secret").remove("iv")
        if (newKey.isNotBlank()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            edit.putString("secret", Base64.encodeToString(cipher.doFinal(newKey.trim().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        check(edit.commit()) { "Could not save provider settings" }
    }
    fun clearKey() { check(prefs.edit().remove("secret").remove("iv").commit()) }
}
