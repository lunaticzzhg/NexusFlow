plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.nexusflow"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.datetime)
    implementation(libs.serialization.core)
    implementation(libs.serialization.json)
    testImplementation(kotlin("test"))
}
