plugins {
    id(ThunderbirdPlugins.Library.android)
}

android {
    namespace = "app.k9mail.feature.migration.launcher.thunderbird"
}

dependencies {
    implementation(projects.feature.migration.launcher.api)
    implementation(projects.feature.migration.qrcode)
    implementation(projects.feature.migration.export)
}

codeCoverage {
    lineCoverage = 0
}
