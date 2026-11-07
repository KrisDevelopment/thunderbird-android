package app.k9mail.feature.migration.export.domain

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import org.junit.Test

class MigrationArchivePrivacyTest {
    @Test
    fun `OAuth state and tokens are excluded from migration`() {
        // Arrange
        val server = createServer("XOAUTH2").copy(
            password = "synthetic-token-in-password-field",
            extra = mapOf("accessToken" to "synthetic-extra-token"),
            oauth = MigrationOAuth(
                clientId = "synthetic-client-id",
                refreshToken = "synthetic-refresh-token",
                accessToken = "synthetic-access-token",
                transferable = true,
            ),
        )
        val testSubject = createAccount(server).copy(oAuthState = "synthetic-serialized-oauth-state")

        // Act
        val result = testSubject.withoutOAuthCredentials()

        // Assert
        assertThat(result.oAuthState).isNull()
        for (migratedServer in listOf(result.incomingServer, result.outgoingServer)) {
            assertThat(migratedServer.password).isNull()
            assertThat(migratedServer.extra).isEqualTo(emptyMap())
            assertThat(migratedServer.oauth?.refreshToken).isNull()
            assertThat(migratedServer.oauth?.accessToken).isNull()
            assertThat(migratedServer.oauth?.clientId).isNull()
            assertThat(migratedServer.oauth?.transferable).isEqualTo(false)
        }
    }

    @Test
    fun `password authentication keeps the password inside the encrypted archive`() {
        // Arrange
        val testSubject = createAccount(createServer("PLAIN").copy(password = "synthetic-password"))

        // Act
        val result = testSubject.withoutOAuthCredentials()

        // Assert
        assertThat(result.incomingServer.password).isEqualTo("synthetic-password")
        assertThat(result.outgoingServer.password).isEqualTo("synthetic-password")
    }

    private fun createServer(authenticationType: String) = MigrationServer(
        type = "imap",
        host = "mail.example.invalid",
        port = 993,
        connectionSecurity = "SSL_TLS_REQUIRED",
        authenticationType = authenticationType,
        username = "user@example.invalid",
    )

    private fun createAccount(server: MigrationServer) = MigrationAccount(
        id = "synthetic-account",
        email = "user@example.invalid",
        incomingServer = server,
        outgoingServer = server.copy(type = "smtp", port = 465),
    )
}
