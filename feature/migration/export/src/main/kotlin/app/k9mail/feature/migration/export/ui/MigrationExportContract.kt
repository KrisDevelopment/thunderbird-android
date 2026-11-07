package app.k9mail.feature.migration.export.ui

import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import net.thunderbird.core.ui.contract.mvi.BaseViewModel

interface MigrationExportContract {

    abstract class ViewModel(initialState: State) : BaseViewModel<State, Event, Effect>(initialState)

    @Stable
    data class State(
        val isLoading: Boolean = false,
        val accounts: ImmutableList<AccountItem> = persistentListOf(),
        val selectedAccountIds: Set<String> = emptySet(),
        val passphrase: String = "",
        val passphraseConfirm: String = "",
        val includeLocalMail: Boolean = false,
        val statusMessage: String? = null,
        val errorMessage: String? = null,
    )

    data class AccountItem(
        val id: String,
        val displayName: String,
        val email: String,
    )

    sealed interface Event {
        data class ToggleAccount(val id: String) : Event
        data class UpdatePassphrase(val value: String) : Event
        data class UpdatePassphraseConfirm(val value: String) : Event
        data class ToggleIncludeLocalMail(val value: Boolean) : Event
        data object StartExport : Event
        data class ExportToUri(val uri: android.net.Uri) : Event
    }

    sealed interface Effect {
        data class LaunchSaveDialog(val suggestedFileName: String) : Effect
        data class ShowMessage(val message: String) : Effect
    }
}