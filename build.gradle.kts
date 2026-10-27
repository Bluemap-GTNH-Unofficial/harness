import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.gtnewhorizons.gtnhconvention")
    // Version comes from GTNHGradle's plugin classpath (Kotlin Gradle plugin 2.2.x).
    id("org.jetbrains.kotlin.jvm")
}

// Git-based versioning is disabled (this project lives in a subdirectory of the BlueMap repo).
val harnessVersion = "0.2.0"
extra["modVersion"] = harnessVersion
version = harnessVersion

// GTNHGradle forces kotlin.jvmToolchain(8); `enableModernJavaSyntax = modern` compiles Java with toolchain 25, so
// Kotlin uses toolchain 25 and emits Java 25 class files too (Kotlin Gradle plugin forced to 2.3.x in
// settings.gradle.kts). Runtime Kotlin stdlib comes from Forgelin 2.0.3-GTNH (2.1.10), so the compiler must not
// emit calls to newer stdlib APIs.
kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        apiVersion.set(KotlinVersion.KOTLIN_2_1)
        languageVersion.set(KotlinVersion.KOTLIN_2_1)
    }
}
