plugins {
    // Lets Gradle auto-provision the Java toolchain below, so the build behaves
    // the same on a laptop and on a CI runner that has no matching JDK.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "quotes"

// The black-box suite. It deliberately has no dependency on the application's code: it talks to the
// real image over HTTP, so the only things it shares with the service are the HTTP contract, the
// database schema (read-only) and the compose files.
include("blackbox")
