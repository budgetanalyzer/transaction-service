import org.springframework.boot.gradle.tasks.run.BootRun

// TC 1.21.4 fixes Docker 29.x compatibility (1.21.3 breaks with "client version 1.32 is too old").
// Spring Boot 3.5.7 manages TC to 1.21.3, so we override it here.
extra["testcontainers.version"] = "1.21.4"

plugins {
    java
    checkstyle
    jacoco
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
}

group = "org.budgetanalyzer"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // Service-web provides core functionality and common utilities
    implementation(libs.service.web)

    // Stack-specific dependencies (required since service-web uses compileOnly)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.springdoc.openapi)

    // Service-specific dependencies
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)
    implementation(libs.pdfbox)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.h2)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        trimTrailingWhitespace()
        endWithNewline()
        importOrder("java", "javax", "jakarta", "org", "com", "", "org.budgetanalyzer")
        removeUnusedImports()
    }
}

checkstyle {
    toolVersion = libs.versions.checkstyle.get()
    config = resources.text.fromUri("https://raw.githubusercontent.com/budgetanalyzer/checkstyle-config/main/checkstyle.xml")
}

val jvmArgsList = listOf(
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED"
)

tasks.withType<BootRun> {
    jvmArgs = jvmArgsList
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs = jvmArgsList
}

tasks.withType<Javadoc> {
    options {
        (this as StandardJavadocDocletOptions).apply {
            addStringOption("Xdoclint:none", "-quiet")
        }
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}
