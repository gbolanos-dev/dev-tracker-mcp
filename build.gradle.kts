subprojects {
    apply(plugin = "java")

    group = "dev.gbolanos"
    version = "0.1.0"

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(17))
            vendor.set(JvmVendorSpec.AZUL)
        }
    }

    repositories {
        mavenCentral()
    }
}
