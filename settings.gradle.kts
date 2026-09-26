plugins {
    // Lets Gradle auto-provision the Java toolchain below, so the build behaves
    // the same on a laptop and on a CI runner that has no matching JDK.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "quotes"
