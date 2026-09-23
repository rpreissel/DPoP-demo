import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Delete

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    kotlin("plugin.jpa") version "2.2.21"
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.dependency.management)
    alias(libs.plugins.kover)
    alias(libs.plugins.openapi.generator)
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
    // Kover haengt jede Test-Task an koverVerify und damit an `build`. Fuer die beiden
    // Snapshot-Tasks ist das falsch: updateOpenApiSnapshot wuerde bei jedem Build api/openapi.yaml
    // stillschweigend neu schreiben - und damit genau den Unterschied glaetten, den
    // checkOpenApiSnapshot melden soll. Beide laufen nur, wenn man sie beim Namen ruft.
    currentProject {
        instrumentation {
            disabledForTestTasks.addAll("checkOpenApiSnapshot", "updateOpenApiSnapshot")
        }
    }
    reports {
        // Eine Sperrklinke, kein Qualitaetsziel: der Wert liegt knapp unter dem heutigen Stand
        // (84 %, Zeilen), damit koverVerify bei einem spuerbaren Rueckgang den Build bricht statt
        // nur einen Bericht zu schreiben. Steigt die Abdeckung, wird die Grenze nachgezogen - nie
        // gesenkt, um eine Aenderung durchzubekommen.
        verify {
            rule {
                minBound(82)
            }
        }
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
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
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
    // Event Publication Registry: Spring Modulith' eigener transaktionaler Outbox
    // (docs/07-betrieb.md Abschnitt 3a). Bringt events-api/-core/-jpa/-jackson mit.
    implementation(libs.spring.modulith.starter.jdbc)
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

tasks.named<Delete>("clean") {
    delete(file("src/main/resources/static"))
}

// `./gradlew bootRunKc` - same as bootRun, just with the `keycloak` profile active (real Keycloak
// via podman-compose, see compose.yml/docs/05-api.md Abschnitt 3, instead of the default
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
    // Der eingecheckte Vertrag ist eine Eingabe der Tests, nicht nur ihre Ausgabe:
    // DiscriminatorMappingTest liest api/openapi.yaml direkt. Ohne diese Zeile haelt Gradle die
    // Tests fuer aktuell, wenn sich nur der Vertrag geaendert hat - der Test laeuft dann nicht und
    // meldet folgerichtig auch nichts.
    inputs.dir(layout.projectDirectory.dir("api")).withPropertyName("apiContract")
    // Die Test-JVMs haengen Agenten an den Bootclasspath (Kover, ByteBuddy). Class Data Sharing
    // bricht dann ab und meldet "Sharing is only supported for boot loader classes" bei jedem
    // Start - aus, statt jedes Mal zu warnen.
    jvmArgs("-Xshare:off")
}

// Der API-Vertrag hat drei Leser (Kotlin-DTOs, Frontend, keycloak-extension); beide Clients werden
// aus api/openapi.yaml generiert. Das ist die eine Stelle, an der er steht; OpenApiSnapshotTest
// prueft ihn gegen den laufenden Code. `check` laeuft ohnehin ueber `test` mit - diese beiden
// Tasks sind nur die benannten Ein- und Ausgaenge dafuer.
val checkOpenApiSnapshot = tasks.register<Test>("checkOpenApiSnapshot") {
    group = "verification"
    description = "Prueft api/openapi.yaml gegen die Spec des laufenden Codes."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("com.example.dpop.orchestrator.api.v1.OpenApiSnapshotTest") }
    // Ein Vertrags-Diff ist kein flaky Test: immer neu ausfuehren, nie aus dem Build-Cache
    // als "up to date" ueberspringen, sonst geht genau die Aenderung durch, die er fangen soll.
    outputs.upToDateWhen { false }
}

tasks.register<Test>("updateOpenApiSnapshot") {
    group = "verification"
    description = "Schreibt api/openapi.yaml aus der Spec des laufenden Codes neu."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("com.example.dpop.orchestrator.api.v1.OpenApiSnapshotTest") }
    systemProperty("openapi.snapshot.update", "true")
    outputs.upToDateWhen { false }
}

// Der Wachposten fuer bereits veroeffentlichte Versionen (docs/05-api.md).
//
// api/published/v1.yaml ist der Vertrag zum Zeitpunkt der Veroeffentlichung und wird NICHT neu
// erzeugt. OpenApiSnapshotTest sichert "Code passt zu api/openapi.yaml"; dieser Vergleich sichert
// "api/openapi.yaml bricht v1 nicht". Der Snapshot-Test macht jede Aenderung sichtbar, sagt aber
// nichts darueber, ob sie brechend ist - und der gewohnte Weg ist dann, den Snapshot nachzuziehen.
//
// Eigene Konfiguration statt testImplementation: openapi-diff bringt swagger-parser mit, das mit
// dem swagger-core von springdoc kollidiert, und jcl-over-slf4j, das mit Boots Logging kollidiert.
val openapiDiff: Configuration = configurations.create("openapiDiff")

dependencies {
    openapiDiff(variantOf(libs.openapi.diff.cli) { classifier("all") })
}

tasks.register<JavaExec>("checkPublishedApiCompatibility") {
    group = "verification"
    description = "Prueft api/openapi.yaml gegen die eingefrorene api/published/v1.yaml."
    classpath = openapiDiff
    mainClass.set("org.openapitools.openapidiff.cli.Main")
    args(
        layout.projectDirectory.file("api/published/v1.yaml").asFile.absolutePath,
        layout.projectDirectory.file("api/openapi.yaml").asFile.absolutePath,
        "--fail-on-incompatible"
    )
    inputs.file(layout.projectDirectory.file("api/published/v1.yaml"))
    inputs.file(layout.projectDirectory.file("api/openapi.yaml"))
    // Wie bei checkOpenApiSnapshot: ein Vertragsbruch darf nie als "up to date" durchgehen.
    outputs.upToDateWhen { false }
}

// Der einzige legitime Weg, die eingefrorene Fassung zu aendern. Ihr Diff in einem PR ist das
// Signal "hier wird eine veroeffentlichte Version angefasst".
tasks.register<Copy>("publishApiVersion") {
    group = "verification"
    description = "Hebt den aktuellen Vertrag zur veroeffentlichten Fassung von v1."
    from(layout.projectDirectory.file("api/openapi.yaml"))
    into(layout.projectDirectory.dir("api/published"))
    rename { "v1.yaml" }
}

// Die dritte Seite des Vertrags: die Frontend-Typen werden aus demselben Snapshot erzeugt, statt
// wie bisher in types.ts von Hand nachgepflegt zu werden. Damit wird eine Backend-DTO-Aenderung
// im Frontend zum TypeScript-Fehler statt zu einem `undefined` zur Laufzeit.
//
// Nur Modelle, keine API-Clients: das Frontend ruft ueber seine eigene DPoP-behaftete api.ts auf
// (jede Anfrage braucht einen frisch signierten Proof), ein generierter fetch-Client koennte das
// nicht und wuerde nur ungenutzt mitlaufen.
val generateFrontendApiTypes = tasks.register<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("generateFrontendApiTypes") {
    group = "frontend"
    description = "Erzeugt frontend/src/generated aus api/openapi.yaml (OpenAPI Generator)."
    generatorName.set("typescript-fetch")
    inputSpec.set(layout.projectDirectory.file("api/openapi.yaml").asFile.absolutePath)
    outputDir.set(layout.projectDirectory.dir("frontend/src/generated").asFile.absolutePath)
    // typescript-fetch buendelt alle Modelle in models/index.ts und fuehrt diese Datei als
    // "supporting file" - ohne den zweiten Eintrag erzeugt der Generator nur die .md-Doku und
    // keine einzige .ts-Datei.
    globalProperties.set(mapOf("models" to "", "supportingFiles" to "index.ts"))
    // Die .md-Doku dupliziert nur, was schon im Snapshot und in den Doc-Kommentaren steht.
    generateModelDocumentation.set(false)
    configOptions.set(
        mapOf(
            // Die erzeugten Interfaces sollen genau die Wire-Namen tragen, damit ein Feld im
            // Frontend so heisst wie im Kotlin-DTO und im Snapshot - sonst waere der Abgleich
            // wieder eine Uebersetzungsleistung von Hand.
            "modelPropertyNaming" to "original",
            "enumPropertyNaming" to "original",
            "supportsES6" to "true",
            "withoutRuntimeChecks" to "true"
        )
    )
    // Der Generator raeumt sein Ausgabeverzeichnis nicht selbst auf: ein geloeschtes DTO liesse
    // sonst seine Datei zurueck und das Frontend koennte weiter dagegen compilieren.
    doFirst { delete(layout.projectDirectory.dir("frontend/src/generated")) }
    // Das oberste index.ts re-exportiert `./runtime` - den fetch-Client, den wir bewusst nicht
    // erzeugen (siehe oben). Die Datei wuerde den Typcheck des Frontends brechen; importiert wird
    // ausschliesslich ./generated/models.
    doLast { delete(layout.projectDirectory.file("frontend/src/generated/index.ts")) }
}

// Bewusst KEIN `npmBuild.dependsOn(generateFrontendApiTypes)`: der Test-Runtime-Classpath zieht
// processResources und damit npmBuild mit, also haenge der Snapshot-Task sonst an einem
// Generatorlauf ueber genau den Snapshot, den er gerade erst schreiben soll - bei ungueltiger Spec
// blockiert sich das gegenseitig. Stattdessen ist frontend/src/generated eingecheckt und die CI
// prueft per `git diff --exit-code`, dass es zum Snapshot passt.
//
// Wohl aber eine Reihenfolge: stehen beide im selben Aufruf (`./gradlew build
// generateFrontendApiTypes`), liest npmBuild das Verzeichnis, das der Generator gerade schreibt, und
// Gradle bricht mit "implicit dependency" ab. mustRunAfter zieht den Generator nicht in einen Lauf,
// der ihn nicht verlangt - die Blockade oben entsteht also nicht -, sorgt aber dafuer, dass npmBuild
// die frischen Typen sieht, wenn er mitlaeuft.
npmBuild { mustRunAfter(generateFrontendApiTypes) }

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
    dependsOn(stageOrchestratorDockerfile, stageKeycloakArtifact)
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