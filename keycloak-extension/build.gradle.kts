plugins {
    java
    alias(libs.plugins.shadow)
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

    // Same JOSE/JWT library AND version the orchestrator's PeerAuthValidator uses - one entry in
    // the version catalog, so ES256 signing on this side and verification on the orchestrator side
    // cannot drift apart. Not provided by Keycloak's runtime, so it has to be shaded into the
    // provider jar.
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.jackson2.databind)
    // Begleiter fuer java.time, den die generierten Vertragsmodelle brauchen (der Vertrag fuehrt
    // date-time-Felder). Dieselbe Jackson-Version, damit nichts auseinanderlaeuft.
    implementation(libs.jackson2.datatype.jsr310)
    // Der Generator schreibt @javax.annotation.Nonnull an jedes Pflichtfeld. compileOnly, weil die
    // Annotation CLASS-Retention hat: der Compiler braucht sie, die Laufzeit nicht - so bleibt sie
    // aus dem Shadow-Jar heraus.
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
    // QR encoding for auth-qr/auth-qr-lookup's WebToolRenderer - core only, no `javase` artifact:
    // the BitMatrix -> PNG conversion is small enough to write directly (QrImageEncoder) without
    // pulling in its extra dependencies.
    implementation("com.google.zxing:core:3.5.3")

    // Nur fuer Tests, die ein ComponentModel in die Hand nehmen (OrchestratorSettingsTest) - zur
    // Laufzeit stellt Keycloak diese Klassen, siehe compileOnly oben.
    testImplementation("org.keycloak:keycloak-server-spi:$keycloakVersion")
    testImplementation("org.keycloak:keycloak-model-storage:$keycloakVersion")
    testImplementation("org.keycloak:keycloak-common:$keycloakVersion")
    testImplementation("org.keycloak:keycloak-core:$keycloakVersion")
    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // KcTextCatalog: eigene Nutzertexte aus den kompilierten Klassen einsammeln (docs/adr/ADR-033).
    testImplementation(libs.asm.tree)
    testImplementation(libs.asm.analysis)
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

// Quellkatalog der eigenen Texte (docs/adr/ADR-033) fuer /translate-texts - Java-Vorlagen (KcText.t,
// KcTexts.of) und Template-Vorlagen (t.of("...") in den .ftl). Landet beim Wurzelprojekt unter
// build/texts/keycloak/, neben den Katalogen von Backend und Frontend.
tasks.register<JavaExec>("exportTexts") {
    group = "texts"
    description = "Schreibt die Text-Vorlagen der Extension nach build/texts/keycloak/texts_source.properties (Wurzelprojekt)."
    dependsOn("testClasses")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.example.dpop.kcext.KcTextCatalog")
    args(
        layout.buildDirectory.dir("classes/java/main").get().asFile.absolutePath,
        layout.projectDirectory.dir("src/main/resources/theme").asFile.absolutePath,
        rootProject.layout.buildDirectory.dir("texts").get().asFile.absolutePath
    )
}

// toolNamesAreTheAppsOwn vergleicht mit dem Frontend-Katalog (docs/adr/ADR-033).
tasks.named<Test>("test") {
    dependsOn(":exportFrontendTexts")
    systemProperty("texts.frontendCatalog", rootProject.file("frontend/build/texts-catalog.json").absolutePath)
}
