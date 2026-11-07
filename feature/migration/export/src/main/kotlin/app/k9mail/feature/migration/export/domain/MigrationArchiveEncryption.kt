package app.k9mail.feature.migration.export.domain

import java.security.SecureRandom
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated encryption for migration archives.
 *
 * Container format (big-endian, all lengths as 4-byte ints):
 *  - magic "TBAM" (4 bytes)
 *  - format version (4 bytes)
 *  - salt (16 bytes)
 *  - nonce/IV (12 bytes)
 *  - ciphertext length (4 bytes)
 *  - ciphertext + GCM tag (variable)
 *
 * Key derivation: PBKDF2WithHmacSHA256, 600000 iterations, 256-bit key.
 * Cipher: AES/GCM/NoPadding with 128-bit authentication tag.
 */
class MigrationArchiveEncryption {

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val salt = randomBytes(SALT_SIZE)
        val nonce = randomBytes(NONCE_SIZE)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)

        val out = java.io.ByteArrayOutputStream()
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        writeInt(out, FORMAT_VERSION)
        out.write(salt)
        out.write(nonce)
        writeInt(out, ciphertext.size)
        out.write(ciphertext)
        return out.toByteArray()
    }

    fun decrypt(container: ByteArray, passphrase: CharArray): ByteArray {
        val input = java.io.ByteArrayInputStream(container)
        val magic = ByteArray(MAGIC.length)
        require(input.read(magic) == magic.size && magic.contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) {
            "Not a Thunderbird migration archive"
        }
        val version = readInt(input)
        require(version == FORMAT_VERSION) { "Unsupported migration archive version: $version" }
        val salt = ByteArray(SALT_SIZE).also { require(input.read(it) == it.size) }
        val nonce = ByteArray(NONCE_SIZE).also { require(input.read(it) == it.size) }
        val ctLen = readInt(input)
        require(ctLen >= TAG_SIZE_BYTES && ctLen == input.available()) {
            "Invalid migration archive length"
        }
        val ciphertext = ByteArray(ctLen).also { require(input.read(it) == it.size) }
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_SIZE_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        val spec = PBEKeySpec(passphrase, salt, KDF_ITERATIONS, KEY_SIZE_BITS)
        try {
            val keyBytes = factory.generateSecret(spec).encoded
            return try {
                SecretKeySpec(keyBytes, "AES")
            } finally {
                keyBytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomBytes(size: Int): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun writeInt(out: java.io.OutputStream, value: Int) {
        DataOutputStream(out).writeInt(value)
    }

    private fun readInt(input: java.io.InputStream): Int {
        return DataInputStream(input).readInt()
    }

    companion object {
        private const val MAGIC = "TBAM"
        private const val FORMAT_VERSION = 1
        private const val SALT_SIZE = 16
        private const val NONCE_SIZE = 12
        private const val TAG_SIZE_BITS = 128
        private const val TAG_SIZE_BYTES = 16
        private const val KEY_SIZE_BITS = 256
        private const val KDF_ITERATIONS = 600_000
        private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
