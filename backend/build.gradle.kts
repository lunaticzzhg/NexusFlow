plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("com.nexusflow.backend.ApplicationKt") }

dependencies {
    implementation(project(":contracts"))
    implementation("io.ktor:ktor-server-core-jvm:${libs.versions.ktor.get()}")
    implementation(libs.ktor.server.di)
    implementation("io.ktor:ktor-server-netty-jvm:${libs.versions.ktor.get()}")
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    runtimeOnly(libs.logback.classic)
    implementation(libs.kotlinx.datetime)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.java.jwt)
    implementation(libs.jwks.rsa)
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:${libs.versions.ktor.get()}")
}
