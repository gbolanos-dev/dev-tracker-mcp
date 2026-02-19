plugins {
    id("com.gradleup.shadow") version "8.3.5"
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":infrastructure"))
    implementation(project(":application"))
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("ch.qos.logback:logback-classic:1.5.16")
}

tasks.shadowJar {
    archiveBaseName.set("dev-tracker-mcp")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes("Main-Class" to "dev.gbolanos.devtracker.server.Main")
    }
}
