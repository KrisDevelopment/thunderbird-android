plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.parcelize)
}

android {
    namespace = "app.k9mail.feature.migration.export"
    resourcePrefix = "migration_export_"
}

dependencies {
    implementation(projects.legacy.core)
    implementation(projects.core.android.account)
    implementation(projects.core.logging.api)
    implementation(projects.core.ui.compose.common)
    implementation(projects.core.ui.navigation)

    implementation(projects.feature.account.api)
    implementation(projects.feature.mail.account.api)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity.compose)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    testImplementation(libs.junit)
    testImplementation(libs.assertk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(projects.core.testing)
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}
