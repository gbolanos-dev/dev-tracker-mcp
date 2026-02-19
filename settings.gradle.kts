plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "dev-tracker-mcp"

include("domain", "infrastructure", "application", "server")
