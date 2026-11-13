import java.time.Duration

plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
    alias(libs.plugins.nexus.publish)
}

group = "net.exoego"
version = System.getenv("RELEASE_VERSION") ?: "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withJavadocJar()
    withSourcesJar()
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

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("META-INF/fuhito/version.properties") {
        expand("version" to version)
    }
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
                implementation(platform(libs.keycloak.parent))
                runtimeOnly(libs.postgresql)
                runtimeOnly(libs.slf4j.simple)
            }
            targets.all {
                testTask.configure {
                    dependsOn(tasks.jar)
                    systemProperty("keycloak.version", testKeycloakVersion.get())
                    systemProperty("provider.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
                    shouldRunAfter(tasks.test)
                    // A hung container call must fail the build, not block it.
                    timeout.set(Duration.ofMinutes(15))
                }
            }
        }
    }
}

tasks.check {
    dependsOn(testing.suites.named("integrationTest"))
}

// The javadoc jar goes to Maven Central, so a warning fails the build. check runs it, so a release does not find it first.
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

// The domain package has no Keycloak and no database, so unit tests must cover all of it.
// The integration tests cover the other packages, and they run in a container where JaCoCo does not see them.
tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            element = "CLASS"
            includes = listOf("net.exoego.keycloak.fuhito.domain.*")
            for (counter in listOf("INSTRUCTION", "BRANCH")) {
                limit {
                    this.counter = counter
                    minimum = "1.0".toBigDecimal()
                }
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
            username.set(System.getenv("SONATYPE_USERNAME"))
            password.set(System.getenv("SONATYPE_PASSWORD"))
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("keycloak-fuhito")
                description.set("Keycloak extension that stores organization structure and its history for each realm")
                url.set("https://github.com/exoego/keycloak-fuhito")

                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("exoego")
                        name.set("TATSUNO Yasuhiro")
                        email.set("ytatsuno.jp@gmail.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/exoego/keycloak-fuhito.git")
                    developerConnection.set("scm:git:ssh://github.com:exoego/keycloak-fuhito.git")
                    url.set("https://github.com/exoego/keycloak-fuhito")
                }
            }
        }
    }
}

signing {
    val signingKey = System.getenv("GPG_PRIVATE_KEY")
    val signingPassword = System.getenv("GPG_PASSPHRASE")
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["maven"])
    }
}
