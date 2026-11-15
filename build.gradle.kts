// Ortak ayarlar. Modüller: :core (saf Java, platform import'u yok), :paper (mevcut eklenti),
// :velocity (proxy eklentisi). Yayınlanan jar'lar: paper/build/libs ve velocity/build/libs.
subprojects {
    group = "dev.nexus"
    version = "1.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }

    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        }
        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(21)
        }
    }
}
