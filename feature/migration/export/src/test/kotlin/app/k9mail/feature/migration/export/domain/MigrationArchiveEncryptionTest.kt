package app.k9mail.feature.migration.export.domain

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import javax.crypto.AEADBadTagException
import org.junit.Test

class MigrationArchiveEncryptionTest {
    @Test
    fun `encrypt then decrypt round trips`() {
        val testSubject = MigrationArchiveEncryption()
        val plain = "hello-migration".toByteArray()
        val pass = "secret-passphrase".toCharArray()
        val container = testSubject.encrypt(plain, pass)
        val out = testSubject.decrypt(container, pass)
        assertThat(out).isEqualTo(plain)
    }

    @Test
    fun `wrong passphrase fails`() {
        val testSubject = MigrationArchiveEncryption()
        val plain = "hello".toByteArray()
        val container = testSubject.encrypt(plain, "right".toCharArray())
        assertFailure { testSubject.decrypt(container, "wrong".toCharArray()) }
            .isInstanceOf(AEADBadTagException::class)
    }

    @Test
    fun `modified ciphertext fails authentication`() {
        val testSubject = MigrationArchiveEncryption()
        val passphrase = "synthetic-passphrase".toCharArray()
        val container = testSubject.encrypt("synthetic-data".toByteArray(), passphrase)
        container[container.lastIndex] = (container.last().toInt() xor 1).toByte()

        assertFailure { testSubject.decrypt(container, passphrase) }
            .isInstanceOf(AEADBadTagException::class)
    }

    @Test
    fun `truncated archive is rejected before allocating ciphertext`() {
        val testSubject = MigrationArchiveEncryption()
        val passphrase = "synthetic-passphrase".toCharArray()
        val container = testSubject.encrypt("synthetic-data".toByteArray(), passphrase)

        assertFailure { testSubject.decrypt(container.copyOf(container.size - 1), passphrase) }
            .isInstanceOf(IllegalArgumentException::class)
    }
}
