import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.21"
    `java-library`
    application
}

group = "com.example.dpop"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

repositories {
    if (gradle.extra["tkNexusReachable"] as Boolean) {
        maven { url = uri("https://nxrm.dst.tk-inline.net/repository/maven-public/") }
    }
    mavenCentral()
}

// keycloak-admin-client wird unabhängig von den Server-SPI-Artefakten released (die laufen bei
// 26.5.5, siehe keycloak-extension/build.gradle.kts) - 26.0.12 ist die letzte auf Maven Central
// verfügbare Version. Das REST-API ist innerhalb von Keycloak 26.x stabil, der Client spricht
// also problemlos mit dem 26.5.5-Server.
val keycloakAdminClientVersion = "26.0.12"
val kotlinVersion = "2.2.21"

dependencies {
    // api statt implementation: org.keycloak.admin.client.Keycloak taucht in der öffentlichen
    // Signatur von buildAdminClient()/MigrationRunner auf - Konsumenten (root-Projekt) müssen den
    // Typ also auf ihrem eigenen Compile-Classpath sehen.
    api("org.keycloak:keycloak-admin-client:$keycloakAdminClientVersion")
    // JVM scripting host: kompiliert und evaluiert die .kc.kts-Migrationsdateien zur Laufzeit,
    // mit KcMigrationScript als impliziter Basisklasse (siehe KcMigrationScript.kt) - dadurch
    // sehen die Skripte die DSL-Funktionen (up/down, KcContext) ohne eigene Imports.
    // kotlin-scripting-jvm-host deklariert seine kotlin.script.experimental.* Abhängigkeiten in
    // der POM nur mit scope=runtime - ohne die drei hier explizit auf implementation zu heben,
    // fehlen sie auf dem Compile-Classpath (ResultValue, ScriptCompilationConfiguration, ...).
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-script-runtime:$kotlinVersion")
}

application {
    mainClass.set("com.example.dpop.kcmigrate.MainKt")
}
