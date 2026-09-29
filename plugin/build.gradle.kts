import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Compile against the Kotlin stdlib bundled with the oldest supported IDE.
        apiVersion = KotlinVersion.KOTLIN_2_2
    }
}

dependencies {
    implementation(project(":core")) {
        // The IDE provides the Kotlin stdlib; bundling another copy is not allowed.
        exclude(group = "org.jetbrains.kotlin")
    }

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
    }
}

intellijPlatform {
    // The plugin has no settings pages to index.
    buildSearchableOptions = false

    pluginConfiguration {
        version = project.version.toString()
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

intellijPlatformTesting {
    runIde {
        // ./gradlew :plugin:runIdeCurrent starts the current stable IntelliJ IDEA with the plugin installed.
        register("runIdeCurrent") {
            type = IntelliJPlatformType.IntellijIdea
            version = providers.gradleProperty("platformVersionCurrent")
        }
    }
}
