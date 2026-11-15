plugins {
    // JDK 21 makinede yoksa Gradle toolchain'i otomatik indirir.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "NexusLogin"

include(":core", ":paper", ":velocity")
