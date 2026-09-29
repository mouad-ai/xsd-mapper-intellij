import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // /core runs inside the IDE against the Kotlin stdlib bundled with the platform.
        apiVersion = KotlinVersion.KOTLIN_2_2
    }
}

dependencies {
    // The IntelliJ Platform settings plugin turns off Kotlin's implicit stdlib dependency for every module.
    implementation(kotlin("stdlib"))
    implementation(libs.xerces) {
        // The JDK already provides the DOM/SAX APIs that xml-apis duplicates.
        exclude(group = "xml-apis", module = "xml-apis")
    }

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val testdataDir = rootProject.layout.projectDirectory.dir("testdata")
val snapshotDir = layout.projectDirectory.dir("src/test/snapshots")

tasks.test {
    useJUnitPlatform()
    inputs.dir(testdataDir).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(snapshotDir).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("xsdmapper.testdata", testdataDir.asFile.absolutePath)
    systemProperty("xsdmapper.snapshots", snapshotDir.asFile.absolutePath)
    // ./gradlew :core:test -Psnapshots.update rewrites snapshot files instead of comparing.
    systemProperty("xsdmapper.snapshots.update", providers.gradleProperty("snapshots.update").isPresent)
    maxHeapSize = "2g"
}
