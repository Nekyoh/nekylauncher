plugins {
    application
}

group = "com.nekyoh"
version = "1.0.0"

repositories {
    mavenCentral()
}

// No third-party dependencies: everything (HTTP, JSON, ZIP, UI) is built on the JDK.
dependencies {
}

application {
    mainClass.set("com.nekyoh.nekylauncher.NekyLauncher")
    applicationName = "NekyLauncher"
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-this-escape"))
}

// Bakes the Microsoft client ID into the build: -PmsClientId=..., gradle.properties or NEKY_MS_CLIENT_ID
val msClientId: String = (project.findProperty("msClientId") as String?)?.takeIf { it.isNotBlank() }
    ?: System.getenv("NEKY_MS_CLIENT_ID") ?: ""

tasks.processResources {
    inputs.property("msClientId", msClientId)
    filesMatching("nekylauncher/build.properties") {
        expand("msClientId" to msClientId)
    }
}

tasks.jar {
    archiveBaseName.set("NekyLauncher")
    archiveVersion.set("")
    manifest {
        attributes(
            "Main-Class" to "com.nekyoh.nekylauncher.NekyLauncher",
            "Implementation-Title" to "NekyLauncher",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "Nekyoh"
        )
    }
}

// Self-tests without external test frameworks: ./gradlew selfTest
sourceSets {
    create("selftest") {
        java.srcDir("src/test/java")
        compileClasspath += sourceSets.main.get().output
        runtimeClasspath += sourceSets.main.get().output
    }
}

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    description = "Runs the launcher self-tests"
    classpath = sourceSets["selftest"].runtimeClasspath
    mainClass.set("com.nekyoh.nekylauncher.SelfTest")
}

tasks.named("check") { dependsOn("selfTest") }
