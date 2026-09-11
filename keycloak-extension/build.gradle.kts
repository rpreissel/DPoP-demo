plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
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
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    // QR encoding for auth-qr/auth-qr-lookup's WebToolRenderer - core only, no `javase` artifact:
    // the BitMatrix -> PNG conversion is small enough to write directly (QrImageEncoder) without
    // pulling in its extra dependencies.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation(platform("org.junit:junit-bom:6.0.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

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
