package app.k9mail.feature.migration.export.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import org.koin.androidx.viewmodel.ext.android.viewModel

class MigrationExportActivity : ComponentActivity() {
    private val viewModel: MigrationExportContract.ViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    MigrationExportScreen(viewModel = viewModel, onNavigateBack = { finish() })
                }
            }
        }
    }
}