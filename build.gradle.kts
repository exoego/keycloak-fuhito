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

testing {
    suites {
        named<JvmTestSuite>("test") {
            useJUnitJupiter(libs.versions.junit)
        }
    }
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
