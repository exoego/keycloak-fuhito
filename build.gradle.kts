import java.time.Duration

plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
    alias(libs.plugins.nexus.publish)
    alias(libs.plugins.errorprone)
    alias(libs.plugins.smallrye.openapi)
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
    // Every warning of the compiler and of Error Prone fails the build.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

repositories {
    mavenCentral()
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

val jacocoContainerAgent: Configuration by configurations.creating

dependencies {
    jacocoContainerAgent("org.jacoco:org.jacoco.agent:${libs.versions.jacoco.get()}:runtime")
    errorprone(libs.errorprone.core)
    compileOnly(platform(libs.keycloak.parent))
    compileOnly(libs.bundles.keycloak.provided)
    compileOnly(libs.microprofile.openapi)
    compileOnly(libs.osgi.versioning)
    testImplementation(platform(libs.keycloak.parent))
    testImplementation(libs.bundles.keycloak.provided)
}

smallryeOpenApi {
    openApiVersion.set("3.2.0")
    outputFileTypeFilter.set("YAML")
    scanPackages.set(listOf("net.exoego.keycloak.fuhito"))
    // The root resource has no path of its own. Keycloak mounts it.
    scanResourceClasses.set(mapOf("net.exoego.keycloak.fuhito.rest.FuhitoResource" to "/"))
}

val generatedOpenApi = tasks.named("generateOpenApiSpec").map {
    layout.buildDirectory.file("generated/openapi/openapi.yaml").get().asFile
}

// docs/openapi.yaml is generated from the annotations of the REST resources. Run this after a change to the API.
tasks.register<Copy>("updateOpenApi") {
    from(generatedOpenApi)
    into("docs")
}

val checkOpenApi = tasks.register("checkOpenApi") {
    val generated = generatedOpenApi
    val committed = layout.projectDirectory.file("docs/openapi.yaml").asFile
    inputs.file(generated)
    inputs.file(committed)
    doLast {
        if (generated.get().readText() != committed.readText()) {
            throw GradleException("docs/openapi.yaml is out of date. Run ./gradlew updateOpenApi and commit the result.")
        }
    }
}

tasks.check {
    dependsOn(checkOpenApi)
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    // The generator merges this file into docs/openapi.yaml. In the jar, Keycloak could take it for a part of its own API.
    exclude("META-INF/openapi.yaml")
    filesMatching("META-INF/fuhito/version.properties") {
        expand("version" to version)
    }
}

// The plugin adds the generated document to the jar. It belongs in docs, not in the provider.
tasks.jar {
    exclude("openapi.yaml")
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
            dependencies {
                implementation(libs.archunit)
            }
        }

        // Runs the built jar in a real Keycloak container with PostgreSQL. Needs Docker.
        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter(libs.versions.junit)
            dependencies {
                implementation(libs.testcontainers.keycloak)
                implementation(libs.testcontainers.postgresql)
                implementation(platform(libs.keycloak.parent))
                // To read the coverage out of the Keycloak container.
                implementation(libs.jacoco.core)
                runtimeOnly(libs.postgresql)
                runtimeOnly(libs.slf4j.simple)
            }
            targets.all {
                testTask.configure {
                    // The tests run the jar, so a new jar must run them again.
                    inputs.files(tasks.jar)
                    systemProperty("keycloak.version", testKeycloakVersion.get())
                    systemProperty("provider.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
                    // The provider runs in the JVM of Keycloak, so the JaCoCo agent goes into that container.
                    val agent = jacocoContainerAgent
                    val coverageDir = layout.buildDirectory.dir("jacoco/keycloak")
                    val loadedClasses = layout.buildDirectory.dir("jacoco-classes/keycloak")
                    inputs.files(agent)
                    outputs.dir(coverageDir)
                    outputs.dir(loadedClasses)
                    doFirst {
                        for (dir in listOf(coverageDir, loadedClasses)) {
                            dir.get().asFile.deleteRecursively()
                            dir.get().asFile.mkdirs()
                        }
                        systemProperty("jacoco.agent", agent.singleFile.absolutePath)
                        systemProperty("jacoco.dir", coverageDir.get().asFile.absolutePath)
                        systemProperty("jacoco.classes", loadedClasses.get().asFile.absolutePath)
                    }
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
    val doclet = options as StandardJavadocDocletOptions
    doclet.addBooleanOption("Werror", true)
    // The REST resources and their records are described by OpenAPI annotations, not by Javadoc.
    doclet.addBooleanOption("Xdoclint:all,-missing", true)
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

// One report for the unit tests and for the integration tests.
// The second part comes out of the Keycloak containers, where the provider really runs.
val coverageData = fileTree(layout.buildDirectory.dir("jacoco")) { include("**/*.exec") }

// Keycloak rewrites some classes when it builds the server. Coverage data fits only the class it was
// measured on, so the report takes each class as Keycloak loaded it, and the compiled class otherwise.
val coverageClasses = tasks.register("coverageClasses") {
    mustRunAfter(tasks.named("integrationTest"))
    val compiled = sourceSets.main.get().output.classesDirs
    val loaded = layout.buildDirectory.dir("jacoco-classes/keycloak")
    val target = layout.buildDirectory.dir("jacoco-classes/report")
    inputs.files(compiled)
    inputs.dir(loaded).optional()
    outputs.dir(target)
    doLast {
        val out = target.get().asFile
        out.deleteRecursively()
        compiled.forEach { dir -> if (dir.exists()) dir.copyRecursively(out, overwrite = true) }
        // The agent names a file "Name.<id>.class". The same class can be written twice, as compiled
        // and as rewritten. The rewritten one is the one that ran.
        val dumped = Regex("""(.+)\.[0-9a-f]{16}\.class""")
        val root = loaded.get().asFile
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            val name = dumped.matchEntire(file.name)?.groupValues?.get(1) ?: return@forEach
            val place = File(out, file.relativeTo(root).parentFile.path + "/" + name + ".class")
            val original = compiled.map { File(it, place.relativeTo(out).path) }.firstOrNull { it.exists() }
            if (original == null || !original.readBytes().contentEquals(file.readBytes())) {
                place.parentFile.mkdirs()
                file.copyTo(place, overwrite = true)
            }
        }
    }
}

tasks.jacocoTestReport {
    mustRunAfter(tasks.test, tasks.named("integrationTest"))
    dependsOn(coverageClasses)
    executionData.setFrom(coverageData)
    classDirectories.setFrom(layout.buildDirectory.dir("jacoco-classes/report"))
    reports {
        xml.required.set(true)
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestReport)
}

// A line that no test runs is a case that cannot happen, or dead code. Look at it before it is merged.
// The rule counts lines. The classes that Keycloak rewrites hold generated methods with no source line,
// so the counts of instructions and branches say little.
val coverageGate = tasks.register<JacocoCoverageVerification>("coverageGate") {
    mustRunAfter(tasks.test, tasks.named("integrationTest"))
    dependsOn(coverageClasses)
    executionData.setFrom(coverageData)
    classDirectories.setFrom(layout.buildDirectory.dir("jacoco-classes/report"))
    sourceDirectories.setFrom(sourceSets.main.get().allJava.srcDirs)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.99".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(coverageGate)
}

// The domain package has no Keycloak and no database, so unit tests must cover all of it.
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
