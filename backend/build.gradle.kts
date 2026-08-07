plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("com.nexusflow.backend.ApplicationKt") }

dependencies {
    implementation(project(":ai"))
    implementation(project(":contracts"))
    implementation("io.ktor:ktor-server-core-jvm:${libs.versions.ktor.get()}")
    implementation("io.ktor:ktor-server-netty-jvm:${libs.versions.ktor.get()}")
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.jsr310)
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host-jvm:${libs.versions.ktor.get()}")
}
