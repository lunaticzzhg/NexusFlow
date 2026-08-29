plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.nexusflow"
version = "0.1.0-SNAPSHOT"

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.datetime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
}
