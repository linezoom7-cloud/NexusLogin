plugins {
    java
    eclipse
}

eclipse {
    classpath {
        plusConfigurations.add(configurations.compileClasspath.get())
        file {
            whenMerged {
                val cp = this as org.gradle.plugins.ide.eclipse.model.Classpath
                cp.entries.forEach { entry ->
                    if (entry is org.gradle.plugins.ide.eclipse.model.Library) {
                        entry.entryAttributes["gradle_used_by_scope"] = "main,test"
                    }
                }
            }
        }
    }
}

group = "dev.nexus"
version = "1.0.4"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Tek kaynak: hem derleme hem de plugin.yml 'libraries' listesi bu sürümleri kullanır.
val hikariVersion = "6.2.1"
val argon2Version = "2.11"
val mariadbVersion = "3.5.1"

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")

    // Netty yalnızca derleme anında gerekir: Paper'ın sunduğu sınıfları kullanırız.
    // Minecraft portu üzerinden HTTP sunumu için kanal (pipeline) kancası.
    compileOnly("io.netty:netty-all:4.1.97.Final")

    // Çalışma zamanında Paper'ın library loader'ı tarafından indirilir (plugin.yml -> libraries).
    // Shade edilmez: argon2-jvm'in JNA native kütüphaneleri relocate edilince bozulur.
    compileOnly("com.zaxxer:HikariCP:$hikariVersion")
    compileOnly("de.mkammerer:argon2-jvm:$argon2Version")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("de.mkammerer:argon2-jvm:$argon2Version")
    testImplementation("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    testImplementation("io.netty:netty-all:4.1.97.Final")
    // Gerçek SQLite üzerinde şema/migration entegrasyon testleri için.
    testImplementation("com.zaxxer:HikariCP:$hikariVersion")
    testImplementation("org.xerial:sqlite-jdbc:3.47.1.0")
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.add("-parameters")
    }

    processResources {
        val props = mapOf(
            "version" to project.version,
            "hikariVersion" to hikariVersion,
            "argon2Version" to argon2Version,
            "mariadbVersion" to mariadbVersion,
        )
        inputs.properties(props)
        filteringCharset = "UTF-8"
        filesMatching("plugin.yml") { expand(props) }
    }

    test {
        useJUnitPlatform()
    }
}
