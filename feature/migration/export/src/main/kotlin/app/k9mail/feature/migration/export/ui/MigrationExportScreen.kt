package app.k9mail.feature.migration.export.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.k9mail.feature.migration.export.R
import net.thunderbird.core.ui.contract.mvi.observe

@Composable
fun MigrationExportScreen(
    viewModel: MigrationExportContract.ViewModel,
    modifier: Modifier = Modifier,
) {
    val dispatchRef = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<((MigrationExportContract.Event) -> Unit)?>(null)
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri?.let { dispatchRef.value?.invoke(MigrationExportContract.Event.ExportToUri(it)) }
    }

    val (state, dispatch) = viewModel.observe { effect ->
        when (effect) {
            is MigrationExportContract.Effect.LaunchSaveDialog -> launcher.launch(effect.suggestedFileName)
            is MigrationExportContract.Effect.ShowMessage -> { }
        }
    }

    androidx.compose.runtime.LaunchedEffect(dispatch) {
        dispatchRef.value = dispatch
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            stringResource(R.string.migration_export_desktop_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Creates an encrypted migration archive (.tbam) that can be imported into " +
                "Thunderbird Desktop. Keep the passphrase safe; it is required for import.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))

        Text("Accounts", style = MaterialTheme.typography.titleMedium)
        state.value.accounts.forEach { account ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = account.id in state.value.selectedAccountIds,
                    onCheckedChange = { dispatch(MigrationExportContract.Event.ToggleAccount(account.id)) },
                )
                Column {
                    Text(account.displayName)
                    Text(account.email, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        PassphraseFields(state = state.value, dispatch = dispatch)

        Spacer(Modifier.height(16.dp))
        if (state.value.isLoading) {
            CircularProgressIndicator()
        } else {
            Button(onClick = { dispatch(MigrationExportContract.Event.StartExport) }) {
                Text("Export")
            }
        }
        state.value.errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        state.value.statusMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.primary)
        }
    }

}

@Composable
private fun PassphraseFields(
    state: MigrationExportContract.State,
    dispatch: (MigrationExportContract.Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = state.passphrase,
            onValueChange = { dispatch(MigrationExportContract.Event.UpdatePassphrase(it)) },
            label = { Text("Passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.passphraseConfirm,
            onValueChange = { dispatch(MigrationExportContract.Event.UpdatePassphraseConfirm(it)) },
            label = { Text("Confirm passphrase") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
