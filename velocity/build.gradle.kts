// Velocity proxy eklentisi. JDBC İÇERMEZ (1.1.0 tasarımı): durum bellekte, DB'ye yalnız Paper yazar.
plugins {
    java
}

base {
    archivesName.set("NexusLogin-Velocity")
}

evaluationDependsOn(":core")
val coreOutput = project(":core").the<SourceSetContainer>()["main"].output

dependencies {
    implementation(project(":core"))
    // 3.4.0 API: 3.4+ ve 4.x proxy'lerinde yüklenir (2026-11-16 düzeneğinde denendi).
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    jar {
        from(coreOutput)
    }
    test {
        useJUnitPlatform()
    }
}
