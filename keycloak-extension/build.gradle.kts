plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
    alias(libs.plugins.openapi.generator)
}

group = "com.example.dpop"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    if (gradle.extra["tkNexusReachable"] as Boolean) {
        maven { url = uri("https://nxrm.dst.tk-inline.net/repository/maven-public/") }
    }
    mavenCentral()
}

val keycloakVersion = "26.6.4"

dependencies {
    // Provided by the Keycloak runtime - not shaded into the provider jar.
    compileOnly("org.keycloak:keycloak-server-spi:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-server-spi-private:$keycloakVersion")
    compileOnly("org.keycloak:keycloak-services:$keycloakVersion")

    // Same JOSE/JWT library the orchestrator's PeerAuthValidator uses (com.nimbusds:nimbus-jose-jwt
    // via Spring Boot's dependency management) - keeps ES256 signing on this side and verification
    // on the orchestrator side speaking exactly the same JOSE dialect. Not provided by Keycloak's
    // runtime, so it has to be shaded into the provider jar.
    implementation("com.nimbusds:nimbus-jose-jwt:9.37.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0")
    // Begleiter fuer java.time, den die generierten Vertragsmodelle brauchen (der Vertrag fuehrt
    // date-time-Felder). Dieselbe Jackson-Version, damit nichts auseinanderlaeuft.
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.19.0")
    // Der Generator schreibt @javax.annotation.Nonnull an jedes Pflichtfeld. compileOnly, weil die
    // Annotation CLASS-Retention hat: der Compiler braucht sie, die Laufzeit nicht - so bleibt sie
    // aus dem Shadow-Jar heraus.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    // QR encoding for auth-qr/auth-qr-lookup's WebToolRenderer - core only, no `javase` artifact:
    // the BitMatrix -> PNG conversion is small enough to write directly (QrImageEncoder) without
    // pulling in its extra dependencies.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/**
 * Getypte Modelle des Orchestrator-Vertrags, erzeugt aus api/openapi.yaml.
 *
 * Vorher las OrchestratorClient den Vertrag von Hand: `json.path("channel").path("state")
 * .asText(null)`. Das ist der schlechteste Fehlermodus - ein umbenanntes Feld liefert `null` statt
 * einer Exception, und der Fehler taucht drei Schichten spaeter auf. Jetzt faellt er beim
 * Compilieren auf.
 *
 * BEWUSST KEINE Task-Kante zum Snapshot-Test des Hauptprojekts. Die haette zwei Probleme: sie
 * schloesse einen Zyklus (stageKeycloakArtifact haengt bereits an :keycloak-extension:shadowJar),
 * und der Test-Classpath des Hauptprojekts zieht ueber processResources den npm-Build mit - genau
 * die Verklemmung, die in dessen build.gradle.kts fuer generateFrontendApiTypes schon dokumentiert
 * ist. api/openapi.yaml ist eingecheckt und damit schlicht eine Eingabedatei.
 *
 * Die Kette schliesst sich trotzdem, ueber zwei getrennte Waechter:
 *   checkOpenApiSnapshot            sichert  Code -> YAML
 *   :keycloak-extension:compileJava sichert  YAML -> Extension
 */
val generateOrchestratorModels = tasks.register<org.openapitools.generator.gradle.plugin.tasks.GenerateTask>("generateOrchestratorModels") {
    group = "build"
    description = "Erzeugt die Vertragsmodelle aus api/openapi.yaml."
    generatorName.set("java")
    inputSpec.set(rootProject.layout.projectDirectory.file("api/openapi.yaml").asFile.absolutePath)
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    modelPackage.set("com.example.dpop.kcext.api.model")
    globalProperties.set(mapOf("models" to "", "supportingFiles" to ""))
    generateModelDocumentation.set(false)
    generateApiDocumentation.set(false)
    configOptions.set(
        mapOf(
            // Jackson 2, wie es dieses Modul ohnehin shaded - so kommt KEINE Abhaengigkeit dazu.
            // (Das Hauptprojekt laeuft auf Jackson 3 / tools.jackson; ein weiterer Grund, warum das
            // Modul eigene Modelle braucht und nicht die Kotlin-DTOs teilen koennte.)
            "serializationLibrary" to "jackson",
            "library" to "native",
            // Die vier verhindern, dass der Generator neue Jars in den Shadow-Jar zieht:
            "annotationLibrary" to "none",
            "documentationProvider" to "none",
            "useBeanValidation" to "false",
            "openApiNullable" to "false",
            // Sonst ist das Ergebnis nie reproduzierbar.
            "hideGenerationTimestamp" to "true",
            "dateLibrary" to "java8"
        )
    )
    doFirst { delete(layout.buildDirectory.dir("generated/openapi")) }
}

sourceSets["main"].java.srcDir(generateOrchestratorModels.map { layout.buildDirectory.dir("generated/openapi/src/main/java") })

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("dpop-demo-keycloak-extension")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
}

tasks.jar {
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
