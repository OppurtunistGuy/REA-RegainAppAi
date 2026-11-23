package app.steady.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the bearer token before it is written to SharedPreferences. The production
 * implementation ([KeystoreTokenCipher]) keeps the key in the Android Keystore, so the token file
 * alone is useless off the device. JVM tests use a fake.
 */
interface TokenCipher {
    /** Returns an opaque, prefs-safe string. */
    fun encrypt(plain: String): String

    /** Null if [stored] cannot be decrypted (key lost after a backup restore or a Keystore reset). */
    fun decrypt(stored: String): String?
}

/**
 * AES-256/GCM with a non-exportable key in `AndroidKeyStore` (alias [ALIAS]). Stored form:
 * `v1:<base64 iv>:<base64 ciphertext+tag>`. The key is created on first use.
 */
class KeystoreTokenCipher : TokenCipher {
    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return listOf(VERSION, b64(cipher.iv), b64(sealed)).joinToString(SEP)
    }

    override fun decrypt(stored: String): String? = try {
        val parts = stored.split(SEP)
        if (parts.size != 3 || parts[0] != VERSION) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, unb64(parts[1])))
            String(cipher.doFinal(unb64(parts[2])), Charsets.UTF_8)
        }
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "steady_token_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION = "v1"
        const val SEP = ":"
    }
}
