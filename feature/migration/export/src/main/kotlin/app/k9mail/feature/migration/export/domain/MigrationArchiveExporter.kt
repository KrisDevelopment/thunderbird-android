package app.k9mail.feature.migration.export.domain

import com.fsck.k9.mail.ServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.thunderbird.core.android.account.Identity
import net.thunderbird.core.android.account.LegacyAccount
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.feature.account.AccountIdFactory
import net.thunderbird.components.core.logging.Logger
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

interface MigrationArchiveExporter {
    /**
     * Exports the selected accounts into [output] as an encrypted migration archive.
     *
     * @return a summary of what was exported.
     */
    suspend fun export(
        accountIds: Set<String>,
        passphrase: CharArray,
        output: OutputStream,
        includeLocalMail: Boolean = false,
    ): MigrationExportResult
}

data class MigrationExportResult(
    val accountCount: Int,
    val warnings: List<String> = emptyList(),
)

class DefaultMigrationArchiveExporter(
    private val accountManager: LegacyAccountManager,
    private val logger: Logger,
    private val encryption: MigrationArchiveEncryption = MigrationArchiveEncryption(),
    private val json: Json = Json { prettyPrint = false; encodeDefaults = true },
) : MigrationArchiveExporter {

    override suspend fun export(
        accountIds: Set<String>,
        passphrase: CharArray,
        output: OutputStream,
        includeLocalMail: Boolean,
    ): MigrationExportResult = withContext(Dispatchers.IO) {
        val warnings = mutableListOf<String>()
        val accounts = accountIds.mapNotNull { uuid ->
            val accountId = net.thunderbird.feature.account.AccountIdFactory.of(uuid)
            val account = accountManager.findById(accountId)
            if (account == null) {
                warnings += "Account $uuid not found, skipped."
                null
            } else {
                account.toMigrationAccount(warnings).withoutOAuthCredentials()
            }
        }

        val archive = MigrationArchive(
            createdAtUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()),
            accounts = accounts,
        )

        val plaintext = json.encodeToString(archive).toByteArray(Charsets.UTF_8)
        // Never leave plaintext on disk; encrypt in memory and stream ciphertext only.
        val ciphertext = try {
            encryption.encrypt(plaintext, passphrase)
        } finally {
            // Wipe plaintext even if encryption fails. Managed strings still cannot be reliably erased.
            plaintext.fill(0)
        }

        output.use { it.write(ciphertext) }
        MigrationExportResult(accountCount = accounts.size, warnings = warnings)
    }

    private fun LegacyAccount.toMigrationAccount(warnings: MutableList<String>): MigrationAccount {
        val incoming = incomingServerSettings.toMigrationServer(this, isOutgoing = false, warnings)
        val outgoing = outgoingServerSettings.toMigrationServer(this, isOutgoing = true, warnings)

        return MigrationAccount(
            id = id.toString(),
            name = name,
            email = email,
            incomingServer = incoming,
            outgoingServer = outgoing,
            identities = identities.map { it.toMigrationIdentity() },
            settings = emptyMap(),
        )
    }

    private fun ServerSettings.toMigrationServer(
        account: LegacyAccount,
        isOutgoing: Boolean,
        warnings: MutableList<String>,
    ): MigrationServer {
        val oauth = if (authenticationType == com.fsck.k9.mail.AuthType.XOAUTH2) {
            // OAuth refresh tokens are NOT assumed transferable. Android Keystore / provider
            // client registration may differ from Desktop. Mark as non-transferable unless
            // explicitly verified; carry state for reauthorization instead.
            warnings += "Account ${account.email}: OAuth (${if (isOutgoing) "SMTP" else "IMAP"}) " +
                "credentials require reauthorization on Desktop; refresh token not transferred."
            MigrationOAuth(
                refreshToken = null,
                transferable = false,
                notes = "Reauthorization required on Thunderbird Desktop.",
            )
        } else null

        return MigrationServer(
            type = type,
            host = host,
            port = port,
            connectionSecurity = connectionSecurity.name,
            authenticationType = authenticationType.name,
            username = username,
            password = password,
            clientCertificateAlias = clientCertificateAlias,
            extra = extra,
            oauth = oauth,
        )
    }

    private fun Identity.toMigrationIdentity(): MigrationIdentity = MigrationIdentity(
        name = name,
        email = email,
        description = description,
        signature = signature,
        signatureUse = signatureUse,
        signatureIsHtml = signatureIsHtml,
        replyTo = replyTo,
    )
}
