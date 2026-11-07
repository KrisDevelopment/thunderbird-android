package app.k9mail.feature.migration.export

import app.k9mail.feature.migration.export.domain.DefaultMigrationArchiveExporter
import app.k9mail.feature.migration.export.domain.MigrationArchiveEncryption
import app.k9mail.feature.migration.export.domain.MigrationArchiveExporter
import app.k9mail.feature.migration.export.ui.MigrationExportContract
import app.k9mail.feature.migration.export.ui.MigrationExportViewModel
import net.thunderbird.core.android.account.LegacyAccountManager
import net.thunderbird.components.core.logging.Logger
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val migrationExportModule = module {
    single { MigrationArchiveEncryption() }
    single<MigrationArchiveExporter> {
        DefaultMigrationArchiveExporter(
            accountManager = get<LegacyAccountManager>(),
            logger = get<Logger>(),
            encryption = get(),
        )
    }
    viewModel<MigrationExportContract.ViewModel> {
        MigrationExportViewModel(
            accountManager = get(),
            exporter = get(),
            contentResolver = androidContext().contentResolver,
        )
    }
}