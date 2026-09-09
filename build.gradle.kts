import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    kotlin("plugin.jpa") version "2.2.21"
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.dependency.management)
    alias(libs.plugins.kover)
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

// Mock/demo-only code is deliberately not held to the same coverage bar as production logic:
// ext_stammdaten simulates the external Personenregister this demo has no real access to, id_eid
// is a "Mock eID" standing in for real AusweisApp/card-reader hardware (see its own Descriptors.kt
// doc comment), and DemoAutoPickNote/DemoInfo/JourneyDebugStep exist purely for the frontend's
// "Struktur" debug view (docs/05-api.md #2, `demo`) - counting them would understate real
// coverage where it matters and overstate it where a gap is harmless.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "com.example.dpop.ext_stammdaten.*",
                    "com.example.dpop.ext_stammdaten.internal.*",
                    "com.example.dpop.id_eid.*",
                    "com.example.dpop.id_eid.internal.*",
                    "com.example.dpop.orchestrator.journey.DemoAutoPickNote",
                    "com.example.dpop.tool_api.DemoInfo",
                    "com.example.dpop.tool_api.JourneyDebugStep"
                )
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// kotlin("plugin.jpa")'s own default annotation preset isn't opening these jakarta.persistence
// entities in practice (verified via javap: getters/classes came out final) - configuring the
// allOpen extension it brings in explicitly is the documented fix so Hibernate can proxy them
// for lazy loading.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

repositories {
    if (gradle.extra["tkNexusReachable"] as Boolean) {
        maven { url = uri("https://nxrm.dst.tk-inline.net/repository/maven-public/") }
    }
    mavenCentral()
}

dependencies {
    implementation(project(":keycloak-migrations"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.kotlin.reflect)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.springdoc.openapi.starter.webmvc.ui)
    runtimeOnly(libs.h2)
    // Spring Boot 4's modular autoconfigure split the H2 console out of the core autoconfigure
    // jar into its own module - without this it silently 404s even with spring.h2.console.enabled=true.
    runtimeOnly(libs.spring.boot.h2console)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.httpclient5)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.modulith.bom.get().toString())
    }
}

val frontendDir = file("frontend")

val npmInstall = tasks.register<Exec>("npmInstall") {
    group = "frontend"
    description = "Installs frontend dependencies"
    workingDir = frontendDir
    inputs.file(frontendDir.resolve("package.json"))
    outputs.dir(frontendDir.resolve("node_modules"))
    commandLine("npm", "install")
}

val npmBuild = tasks.register<Exec>("npmBuild") {
    group = "frontend"
    description = "Builds the frontend and copies it to src/main/resources/static"
    dependsOn(npmInstall)
    workingDir = frontendDir
    inputs.dir(frontendDir.resolve("src"))
    inputs.file(frontendDir.resolve("vite.config.ts"))
    inputs.file(frontendDir.resolve("package.json"))
    outputs.dir(file("src/main/resources/static"))
    commandLine("npm", "run", "build")
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(npmBuild)
}

// `./gradlew bootRunKc` - same as bootRun, just with the `keycloak` profile active (real Keycloak
// via podman-compose, see compose.yml/docs/ideen/web-keycloak-kanal.md, instead of the default
// profile's Mock-Keycloak frontend). Equivalent to `bootRun --args='--spring.profiles.active=keycloak'`,
// just without having to remember/retype that every time.
tasks.register<org.springframework.boot.gradle.tasks.run.BootRun>("bootRunKc") {
    group = "application"
    description = "Runs the app with the 'keycloak' Spring profile active (real Keycloak via podman-compose)."
    classpath = sourceSets["main"].runtimeClasspath
    // Hardcoded rather than derived from the "bootRun" task's own resolved mainClass: reading a
    // Provider off another task creates an implicit Gradle task dependency, which for "bootRun"
    // itself means actually EXECUTING it (launching the app under the default profile) as a
    // "configuration" step - it blocks, so bootRunKc would never even start.
    mainClass.set("com.example.dpop.DpopApplicationKt")
    systemProperty("spring.profiles.active", "keycloak")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Trennt Gradle-Build von Podman-Build: die Dockerfiles (mitsamt sich selbst, siehe
// stageOrchestratorDockerfile/stageKeycloakArtifact unten) kopieren nur noch fertige Artefakte aus
// build/podman/*, statt Gradle/npm selbst innerhalb des Containers laufen zu lassen und statt dass
// Podman das gesamte Repo als Build-Kontext einlesen muesste. Muss vor `podman-compose
// build`/`up --build` einmal laufen: `./gradlew stagePodmanArtifacts`.
val podmanStageDir = layout.buildDirectory.dir("podman")

val stageOrchestratorArtifact = tasks.register<Exec>("stageOrchestratorArtifact") {
    group = "podman"
    description = "Entpackt den Boot-Jar in einen flachen Classpath unter build/podman/orchestrator."
    dependsOn(tasks.named("bootJar"))
    val destination = podmanStageDir.map { it.dir("orchestrator") }
    val bootJarFile = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar").flatMap { it.archiveFile }
    inputs.file(bootJarFile)
    outputs.dir(destination)
    doFirst {
        destination.get().asFile.deleteRecursively()
    }
    // kotlin-scripting-jvm-host (KeycloakMigrationRunnerStartup) bricht in der gepackten
    // Boot-Fat-Jar ab (siehe Dockerfile-Kommentar) - jarmode=tools extract liefert stattdessen
    // einen klassischen, flachen Classpath (Haupt-Jar + lib/*.jar).
    commandLine(
        "java", "-Djarmode=tools", "-jar", bootJarFile.get().asFile.absolutePath,
        "extract", "--destination", destination.get().asFile.absolutePath,
    )
    // Die extrahierte Haupt-Jar behaelt ihren urspruenglichen Namen (z.B.
    // dpop-demo-0.0.1-SNAPSHOT.jar), nicht "app.jar" - explizit umbenennen, weil das Dockerfile
    // per fixem Namen darauf zugreift (ENTRYPOINT -cp 'app.jar:lib/*').
    doLast {
        val destDir = destination.get().asFile
        val extractedJar = destDir.listFiles { f -> f.isFile && f.extension == "jar" }!!.single()
        extractedJar.renameTo(destDir.resolve("app.jar"))
    }
}

val stageOrchestratorMigrations = tasks.register<Copy>("stageOrchestratorMigrations") {
    group = "podman"
    dependsOn(stageOrchestratorArtifact)
    from("keycloak-migrations/migrations")
    into(podmanStageDir.map { it.dir("orchestrator/keycloak-migrations/migrations") })
}

// Kopiert das Dockerfile mit nach build/podman/orchestrator, damit compose.yml dieses
// Staging-Verzeichnis (statt des gesamten Repo-Wurzelverzeichnisses) als Build-Kontext angeben
// kann: Podman muss dann nur noch die paar fertigen Artefakte hochladen/hashen, nicht mehr .git,
// node_modules, Gradle-Caches etc. erst durchlaufen. Das Dockerfile selbst nutzt bereits
// kontext-relative COPY-Pfade, keine Umschreibung noetig.
val stageOrchestratorDockerfile = tasks.register<Copy>("stageOrchestratorDockerfile") {
    group = "podman"
    description = "Kopiert das Dockerfile nach build/podman/orchestrator."
    dependsOn(stageOrchestratorArtifact)
    from("Dockerfile")
    into(podmanStageDir.map { it.dir("orchestrator") })
}

val stageKeycloakArtifact = tasks.register<Copy>("stageKeycloakArtifact") {
    group = "podman"
    description = "Kopiert Extension-Shadow-Jar, Theme, Healthcheck und Dockerfile nach build/podman/keycloak."
    dependsOn(":keycloak-extension:shadowJar")
    from(project(":keycloak-extension").tasks.named("shadowJar")) {
        rename { "dpop-demo-keycloak-extension.jar" }
    }
    from("keycloak-extension/src/main/resources/theme") {
        into("theme")
    }
    from("keycloak-extension/healthcheck/JwksHealthCheck.java") {
        into("healthcheck")
    }
    from("keycloak-extension/Dockerfile")
    into(podmanStageDir.map { it.dir("keycloak") })
}

val stagePodmanArtifacts = tasks.register("stagePodmanArtifacts") {
    group = "podman"
    description = "Baut Orchestrator-Jar, Frontend und Keycloak-Extension und legt beide unter build/podman ab, damit podman-compose build/up nur noch fertige Artefakte kopiert."
    dependsOn(stageOrchestratorMigrations, stageOrchestratorDockerfile, stageKeycloakArtifact)
}

// Haengt das Staging an den normalen Build-Lifecycle: wer `./gradlew build`/`assemble` laufen
// laesst, bekommt build/podman automatisch aktuell mit - kein separater, leicht zu vergessender
// Handaufruf von stagePodmanArtifacts noetig, bevor `podman-compose build`/`up --build` folgt.
// Gradle selbst ruft dabei kein podman-compose auf - das bleibt bewusst ein eigener,
// von aussen angestossener Schritt (Podman-Aufrufe brauchen ggf. eine laufende Podman-Machine
// bzw. Rootless-Setup, das ein Gradle-Build nicht voraussetzen sollte).
tasks.named("assemble") {
    dependsOn(stagePodmanArtifacts)
}