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
    mavenCentral()
}

dependencies {
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

tasks.withType<Test> {
    useJUnitPlatform()
}