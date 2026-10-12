plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.expensemail.app"
    compileSdk = 35

    val betaKeystorePath = System.getenv("BETA_KEYSTORE_PATH")
    val betaStorePassword = System.getenv("BETA_STORE_PASSWORD")
    val betaKeyAlias = System.getenv("BETA_KEY_ALIAS")
    val betaKeyPassword = System.getenv("BETA_KEY_PASSWORD")
    val hasBetaSigning = listOf(betaKeystorePath, betaStorePassword, betaKeyAlias, betaKeyPassword).all { !it.isNullOrBlank() }

    defaultConfig {
        applicationId = "com.expensemail.app"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("CI_VERSION_CODE")?.toIntOrNull() ?: 2
        versionName = System.getenv("CI_VERSION_NAME") ?: "1.1.0"
        manifestPlaceholders["appLabel"] = "Pocketlog"
    }
    if (hasBetaSigning) {
        signingConfigs {
            create("beta") {
                storeFile = file(betaKeystorePath!!)
                storePassword = betaStorePassword
                keyAlias = betaKeyAlias
                keyPassword = betaKeyPassword
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("beta")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
