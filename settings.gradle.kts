plugins {
    // jvmToolchain(25) needs a JDK 25 the build machine may not have: IONNexus's auto-build container
    // ships only JDK 21 and gives every repository its own Gradle home, so nothing another build
    // downloaded is visible here. Same resolver IONPlugins uses.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "WorldEditMinestom"
