import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ktlint)
}

kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(libs.koin.core)
            implementation(libs.navigation.compose)
            implementation(libs.serialization.core)
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:${libs.versions.activityCompose.get()}")
        }
    }
}

android {
    namespace = "com.nexusflow.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nexusflow.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

configure<KtlintExtension> {
    filter {
        exclude("**/generated/**")
    }
}
