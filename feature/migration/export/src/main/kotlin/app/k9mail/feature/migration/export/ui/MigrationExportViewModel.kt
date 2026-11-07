package app.k9mail.feature.migration.export.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.k9mail.feature.migration.export.domain.MigrationArchiveExporter
import app.k9mail.feature.migration.export.domain.MigrationExportResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.thunderbird.core.android.account.LegacyAccountManager

class MigrationExportViewModel(
    private val accountManager: LegacyAccountManager,
    private val exporter: MigrationArchiveExporter,
    private val contentResolver: ContentResolver,
) : MigrationExportContract.ViewModel(initialState = MigrationExportContract.State()) {

    init {
        loadAccounts()
    }

    private fun loadAccounts() {
        val items = accountManager.findAll().map { account ->
            MigrationExportContract.AccountItem(
                id = account.id.toString(),
                displayName = account.name ?: account.email,
                email = account.email,
            )
        }
        updateState { it.copy(accounts = kotlinx.collections.immutable.persistentListOf(*items.toTypedArray())) }
    }

    override fun event(event: MigrationExportContract.Event) {
        when (event) {
            is MigrationExportContract.Event.ToggleAccount -> toggleAccount(event.id)

            is MigrationExportContract.Event.UpdatePassphrase -> updateState { it.copy(passphrase = event.value) }

            is MigrationExportContract.Event.UpdatePassphraseConfirm -> updateState {
                it.copy(passphraseConfirm = event.value)
            }

            is MigrationExportContract.Event.ToggleIncludeLocalMail -> updateState {
                it.copy(includeLocalMail = event.value)
            }

            MigrationExportContract.Event.StartExport -> startExport()

            is MigrationExportContract.Event.ExportToUri -> exportToUri(event.uri)
        }
    }

    private fun toggleAccount(id: String) {
        updateState { state ->
            val selected = state.selectedAccountIds.toMutableSet()
            if (id in selected) selected.remove(id) else selected.add(id)
            state.copy(selectedAccountIds = selected)
        }
    }

    private fun startExport() {
        val state = state.value
        val errorMessage = when {
            state.selectedAccountIds.isEmpty() -> "Select at least one account."
            state.passphrase.length < MIN_PASSPHRASE_LENGTH -> "Passphrase must be at least 8 characters."
            state.passphrase != state.passphraseConfirm -> "Passphrases do not match."
            else -> null
        }
        if (errorMessage != null) {
            updateState { it.copy(errorMessage = errorMessage) }
            return
        }
        updateState { it.copy(errorMessage = null, statusMessage = null) }
        emitEffect(MigrationExportContract.Effect.LaunchSaveDialog("thunderbird-migration.tbam"))
    }

    // SAF/account providers can throw different exception types. Display a sanitized failure rather
    // than exposing private exception messages or logging credential-bearing data.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun exportToUri(uri: Uri) {
        val state = state.value
        val passphrase = state.passphrase.toCharArray()
        viewModelScope.launch {
            updateState { it.copy(isLoading = true, errorMessage = null) }
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    val result: MigrationExportResult = exporter.export(
                        accountIds = state.selectedAccountIds,
                        passphrase = passphrase,
                        output = stream,
                        includeLocalMail = state.includeLocalMail,
                    )
                    updateState {
                        it.copy(
                            isLoading = false,
                            statusMessage = "Exported ${result.accountCount} account(s)." +
                                if (result.warnings.isNotEmpty()) {
                                    " Warnings: ${result.warnings.joinToString()}"
                                } else {
                                    ""
                                },
                        )
                    }
                } ?: updateState {
                    it.copy(isLoading = false, errorMessage = "Could not open destination.")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Exception messages from account/storage libraries may contain private values.
                updateState { it.copy(isLoading = false, errorMessage = "Export failed.") }
            } finally {
                passphrase.fill('\u0000')
                // Best-effort wipe of passphrase from VM state.
                updateState { it.copy(passphrase = "", passphraseConfirm = "") }
            }
        }
    }

    private companion object {
        const val MIN_PASSPHRASE_LENGTH = 8
    }
}
