plugins {
    // Downloads JDK 25 automatically when the machine running the build does not
    // already have it installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "TridentPlus"
