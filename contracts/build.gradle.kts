plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.nexusflow"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
}
