package no.vardir.varde

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val alias = "varde-session"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(): SecretKey {
        return (keyStore().getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance("AES","AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun save(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val value = Base64.encodeToString(cipher.iv,Base64.NO_WRAP) + ":" +
            Base64.encodeToString(cipher.doFinal(token.toByteArray()),Base64.NO_WRAP)
        check(prefs.edit().putString("token",value).commit()) { "Could not save session" }
    }

    /**
     * The saved token, or null. The token is only deleted when it can never be decrypted again (the key is gone or the
     * data is damaged). Other keystore errors can be temporary, so the token is kept for the next launch.
     */
    fun load(): String? {
        val stored = prefs.getString("token", null) ?: return null
        val parts = stored.split(":")
        if (parts.size != 2) { clear(); return null }
        return try {
            val key = keyStore().getKey(alias, null) as? SecretKey ?: run { clear(); return null }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            if (e is AEADBadTagException || e is BadPaddingException || e is KeyPermanentlyInvalidatedException ||
                e is UnrecoverableKeyException || e is IllegalArgumentException) clear()
            null
        }
    }
    fun clear() { prefs.edit().remove("token").commit() }
}
