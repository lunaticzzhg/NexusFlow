import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ktlint)
    id("com.google.devtools.ksp")
    id("de.jensklingenberg.ktorfit")
}

val generatedKspDirectory = layout.buildDirectory.dir("generated/ksp").get().asFile

kotlin {
    androidTarget()
    iosArm64()
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(project(":contracts"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.resources)
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.navigation.compose)
            implementation(libs.serialization.core)
            implementation(libs.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation("de.jensklingenberg.ktorfit:ktorfit-lib-light:2.5.2")
        }
        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:${libs.versions.activityCompose.get()}")
            implementation(libs.ktor.client.okhttp)
            implementation(libs.androidx.credentials)
            implementation(libs.androidx.credentials.play.services.auth)
            implementation(libs.googleid)
            implementation(libs.security.crypto)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

val maintainedCommonMainSources =
    kotlin.sourceSets
        .getByName("commonMain")
        .kotlin
        .srcDirs
        .filterNot { directory -> directory.absolutePath.startsWith(generatedKspDirectory.absolutePath) }

tasks
    .matching {
        it.name == "runKtlintCheckOverCommonMainSourceSet" ||
            it.name == "runKtlintFormatOverCommonMainSourceSet"
    }.configureEach {
        dependsOn("kspCommonMainKotlinMetadata")
        (this as BaseKtLintCheckTask).setSource(maintainedCommonMainSources)
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
        buildConfigField(
            "String",
            "GOOGLE_SERVER_CLIENT_ID",
            "\"${providers.gradleProperty("GOOGLE_SERVER_CLIENT_ID").getOrElse("")}\"",
        )
    }
    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${providers.gradleProperty("DEBUG_API_BASE_URL").getOrElse("http://127.0.0.1:8080")}\"",
            )
        }
        release {
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${providers.gradleProperty("RELEASE_API_BASE_URL").getOrElse("")}\"",
            )
        }
    }
    buildFeatures {
        buildConfig = true
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

tasks.withType<BaseKtLintCheckTask>().configureEach {
    doFirst {
        val generatedDirectory = layout.buildDirectory.get().asFile.absolutePath
        setSource(source.files.filterNot { file -> file.absolutePath.startsWith(generatedDirectory) })
    }
}
