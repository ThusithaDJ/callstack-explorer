import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.25"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

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
        instrumentationTools()
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
            // Deliberately left unset: the Plugin Verifier rejects a blank
            // until-build attribute as an invalid build number, and this repo
            // has only actually been verified against 2024.2. Falls back to
            // this Gradle plugin's default convention ("242.*"); widen this
            // once compatibility with newer IDE major versions is confirmed.
        }
    }

    // Requires a Marketplace Permanent Token: https://plugins.jetbrains.com/author/me/tokens
    // Run with: PUBLISH_TOKEN=xxx ./gradlew publishPlugin
    publishing {
        token.set(providers.environmentVariable("PUBLISH_TOKEN"))
    }

    pluginVerification {
        ides {
            // recommended() tries to resolve the latest IDE build, which can
            // fail if this Gradle plugin version predates that release's
            // download URL scheme. Verify against the version we actually
            // build/test against instead (already cached locally from runIde).
            ide(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
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
