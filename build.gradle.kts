import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.9.0"
}

// A locally installed, newer IDE, used by `./gradlew runIdeLatest` (sandbox) and by
// verifyPlugin. The plugin itself still compiles against platformVersion (2024.2).
// 2024.2's bundled Gradle plugin errors on startup ("IllegalArgumentException: 25")
// because it can't parse newer Java versions; newer IDEs don't have that problem.
// Override the install location with -PrunIdeLocalPath=... or in ~/.gradle/gradle.properties.
val runIdeLocalPath: Provider<String> = providers.gradleProperty("runIdeLocalPath")
    .orElse("C:/Program Files/JetBrains/IntelliJ IDEA 2026.1")

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        bundledPlugin("com.intellij.java")
        pluginVerifier()
    }

    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginConfiguration {
        id.set(providers.gradleProperty("pluginGroup"))
        name.set(providers.gradleProperty("pluginName"))
        version.set(providers.gradleProperty("pluginVersion"))

        ideaVersion {
            sinceBuild.set(providers.gradleProperty("pluginSinceBuild"))
            untilBuild.set(provider { null })
        }
    }

    // Requires a Marketplace Permanent Token: https://plugins.jetbrains.com/author/me/tokens
    // Run with: PUBLISH_TOKEN=xxx ./gradlew publishPlugin
    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }

    pluginVerification {
        ides {
            // Verifies against the platform we build against (2024.2, since-build 242).
            // Most users run recent releases (June 2026: 2025.1+ ≈ 76%), so also check
            // the newest before publishing. local(...) here trips a bug in Gradle plugin
            // 2.9.0, so run the verifier CLI against the local install instead - see README.
            @Suppress("DEPRECATION") // create(...) clashes with the main platform dependency in 2.9.0
            ide(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        }
    }
}

intellijPlatformTesting {
    runIde {
        register("runIdeLatest") {
            localPath.set(file(runIdeLocalPath.get()))
        }
    }
}

tasks {
    withType<KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    test {
        useJUnitPlatform()
    }
}
