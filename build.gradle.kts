import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    java
    checkstyle
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
    // Service-web provides: web, data-jpa, springdoc, jackson, slf4j, opencsv, actuator
    implementation(libs.service.web)

    // Service-specific dependencies
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.flyway.core)
    implementation(libs.flyway.database.postgresql)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.h2)
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
}

tasks.named("check") {
    dependsOn("spotlessCheck")
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
