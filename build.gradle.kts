plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "dev.tridentplus"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.134-stable")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    // Paper's registry API is still marked experimental; the warnings are expected.
    options.compilerArgs.add("-Xlint:-removal")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
}

tasks.named<xyz.jpenilla.runpaper.task.RunServer>("runServer") {
    minecraftVersion("26.2")
    runDirectory.set(layout.projectDirectory.dir("run"))
    jvmArgs("-Xms1G", "-Xmx2G")
    // Paper 26.2 requires Java 25; do not inherit whatever JDK happens to run Gradle.
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    doFirst {
        // run-paper does not write these files for us. EULA acceptance for a local,
        // disposable development server is convenient, and the properties match the
        // test checklist in the README.
        val dir = runDirectory.get().asFile
        dir.mkdirs()
        dir.resolve("eula.txt").writeText("eula=true\n")
        dir.resolve("server.properties").writeText(
            """
            online-mode=false
            level-type=flat
            gamemode=creative
            difficulty=easy
            spawn-monsters=false
            spawn-protection=0
            max-players=5
            view-distance=6
            """.trimIndent() + "\n"
        )
    }
}
