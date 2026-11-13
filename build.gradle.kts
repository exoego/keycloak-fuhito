plugins {
    `java-library`
    jacoco
}

group = "net.exoego"
version = System.getenv("RELEASE_VERSION") ?: "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(platform(libs.keycloak.parent))
    compileOnly(libs.bundles.keycloak.provided)
    testImplementation(platform(libs.keycloak.parent))
    testImplementation(libs.bundles.keycloak.provided)
}

tasks.withType<Jar>().configureEach {
    from(files("LICENSE", "NOTICE")) {
        into("META-INF")
    }
}

// An empty value, as from a CI matrix row without it, means the version of the catalog.
val testKeycloakVersion = providers.gradleProperty("testKeycloakVersion")
    .filter { it.isNotBlank() }
    .orElse(libs.versions.keycloak)

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit)
        }

        // Runs the built jar in a real Keycloak container with PostgreSQL. Needs Docker.
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(libs.testcontainers.keycloak)
                implementation(libs.testcontainers.postgresql)
                runtimeOnly(libs.slf4j.simple)
            }
            targets.all {
                testTask.configure {
                    dependsOn(tasks.jar)
                    systemProperty("keycloak.version", testKeycloakVersion.get())
                    systemProperty("provider.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
                    shouldRunAfter(tasks.test)
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("integrationTest"))
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Werror", true)
}

tasks.check {
    dependsOn(tasks.javadoc)
}

val testJavaVersion = providers.gradleProperty("testJavaVersion").orElse("21")
tasks.withType<Test>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(testJavaVersion.map { JavaLanguageVersion.of(it) })
    })
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
    }
}
