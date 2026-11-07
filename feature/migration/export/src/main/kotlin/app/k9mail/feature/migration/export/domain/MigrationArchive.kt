package app.k9mail.feature.migration.export.domain

import kotlinx.serialization.Serializable

/**
 * Versioned migration archive exchanged between Thunderbird for Android and Thunderbird Desktop.
 *
 * The on-disk archive is an encrypted container (see [MigrationArchiveEncryption]) wrapping a
 * single JSON document whose top-level object is a [MigrationArchive].
 */
@Serializable
data class MigrationArchive(
    val format: String = FORMAT_ID,
    val version: Int = FORMAT_VERSION,
    val createdAtUtc: String,
    val sourceApp: String = "net.thunderbird.android",
    val sourceVersion: String? = null,
    val accounts: List<MigrationAccount> = emptyList(),
    val globalSettings: Map<String, String> = emptyMap(),
    val certificates: List<MigrationCertificate> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) {
    companion object {
        const val FORMAT_ID = "thunderbird-android-to-desktop"
        const val FORMAT_VERSION = 1
    }
}

@Serializable
data class MigrationAccount(
    val id: String,
    val name: String? = null,
    val email: String,
    val incomingServer: MigrationServer,
    val outgoingServer: MigrationServer,
    val identities: List<MigrationIdentity> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    val folderSettings: List<MigrationFolder> = emptyList(),
    val avatarImageBase64: String? = null,
    val oAuthState: String? = null,
)

@Serializable
data class MigrationServer(
    val type: String,
    val host: String,
    val port: Int,
    val connectionSecurity: String,
    val authenticationType: String,
    val username: String,
    val password: String? = null,
    val clientCertificateAlias: String? = null,
    val extra: Map<String, String?> = emptyMap(),
    val oauth: MigrationOAuth? = null,
)

@Serializable
data class MigrationOAuth(
    val provider: String? = null,
    val clientId: String? = null,
    val refreshToken: String? = null,
    val accessToken: String? = null,
    val expiresAt: Long? = null,
    val scopes: List<String> = emptyList(),
    val transferable: Boolean = false,
    val notes: String? = null,
)

@Serializable
data class MigrationIdentity(
    val name: String? = null,
    val email: String? = null,
    val description: String? = null,
    val signature: String? = null,
    val signatureUse: Boolean = false,
    val signatureIsHtml: Boolean = false,
    val replyTo: String? = null,
    val settings: Map<String, String> = emptyMap(),
)

@Serializable
data class MigrationFolder(
    val serverId: String,
    val integrate: Boolean = true,
    val inTopGroup: Boolean = false,
    val syncEnabled: Boolean = true,
    val visible: Boolean = true,
    val notificationsEnabled: Boolean = true,
    val pushEnabled: Boolean = false,
)

@Serializable
data class MigrationCertificate(
    val alias: String,
    val type: String,
    val encodedBase64: String,
    val transferable: Boolean = false,
)

// Only credentials that Desktop can use belong in the encrypted document. In particular, Android's
// serialized OAuth state and arbitrary protocol extras must not carry tokens into a backup.
internal fun MigrationAccount.withoutOAuthCredentials(): MigrationAccount = copy(
    oAuthState = null,
    incomingServer = incomingServer.withoutOAuthCredentials(),
    outgoingServer = outgoingServer.withoutOAuthCredentials(),
)

private fun MigrationServer.withoutOAuthCredentials(): MigrationServer = copy(
    password = password.takeIf { authenticationType == "PLAIN" || authenticationType == "CRAM_MD5" },
    extra = emptyMap(),
    oauth = if (authenticationType == "XOAUTH2" || oauth != null) {
        MigrationOAuth(notes = "Reauthorization required on Thunderbird Desktop.")
    } else {
        null
    },
)
