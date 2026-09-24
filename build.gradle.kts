import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import io.gitlab.arturbosch.detekt.getSupportedKotlinVersion
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.kover)
    base
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.kotlin.jpa) apply false
}

val rootLibs = libs
val kotlinModules = subprojects.filter { it.name != "chat-runtime-config" }

allprojects {
    group = "com.chat"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}

configure(kotlinModules) {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlinx.kover")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")

    dependencies {
        val implementation by configurations
        val testImplementation by configurations
        val testRuntimeOnly by configurations

        implementation(rootLibs.kotlin.reflect)
        implementation(rootLibs.jackson.module.kotlin)
        testImplementation(rootLibs.spring.boot.starter.test)
        testImplementation(rootLibs.kotlin.test.junit5)
        testRuntimeOnly(rootLibs.junit.platform.launcher)
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    extensions.configure<KtlintExtension> {
        version.set(rootLibs.versions.ktlint.get())
        outputToConsole.set(true)
        baseline.set(rootProject.file("config/ktlint/baseline-${project.name}.xml"))
        filter {
            exclude("**/build/**", "**/.gradle/**", "**/node_modules/**")
        }
    }
}

configure(kotlinModules) {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    // Register after Spring's dependency management resolution rules.
    afterEvaluate {
        configurations.named("detekt") {
            resolutionStrategy.eachDependency {
                if (requested.group == "org.jetbrains.kotlin") {
                    useVersion(getSupportedKotlinVersion())
                }
            }
        }
    }
    extensions.configure<DetektExtension> {
        buildUponDefaultConfig = true
        val packageConfig = rootProject.file("config/detekt/packages/${project.name}.yml")
        config.setFrom(
            listOf(packageConfig, rootProject.file("config/detekt/detekt.yml")).filter { it.exists() },
        )
        baseline = rootProject.file("config/detekt/baseline-${project.name}.xml")
        source.setFrom(files("src/main/kotlin", "src/test/kotlin"))
    }
    tasks.named("check") {
        dependsOn("detektMain", "detektTest")
    }
    tasks.withType<Detekt>().configureEach {
        jvmTarget = "21"
        reports {
            html.required.set(true)
            xml.required.set(true)
            sarif.required.set(true)
        }
    }
}

val verifyDetekt by tasks.registering {
    group = "verification"
    description = "Checks Kotlin defects and complexity with type resolution in all modules."
    dependsOn(kotlinModules.flatMap { listOf("${it.path}:detektMain", "${it.path}:detektTest") })
}

val verifyKotlinFormat by tasks.registering {
    group = "verification"
    description = "Checks Kotlin and Gradle Kotlin DSL formatting."
    dependsOn(":ktlintCheck", *subprojects.map { "${it.path}:ktlintCheck" }.toTypedArray())
}

val verifyKotlinQuality by tasks.registering {
    group = "verification"
    description = "Checks typed Kotlin code quality and formatting without running tests."
    dependsOn(verifyDetekt, verifyKotlinFormat)
}

tasks.register("formatKotlin") {
    group = "formatting"
    description = "Formats Kotlin and Gradle Kotlin DSL files in all modules."
    dependsOn(":ktlintFormat", *subprojects.map { "${it.path}:ktlintFormat" }.toTypedArray())
}

tasks.named("check") {
    dependsOn(verifyKotlinQuality, "koverVerify", *subprojects.map { "${it.path}:check" }.toTypedArray())
}

dependencies {
    kotlinModules.forEach { kover(project(it.path)) }
}

kover {
    reports {
        total {
            html { onCheck = true }
            xml { onCheck = true }
            verify {
                rule {
                    minBound(78, CoverageUnit.LINE)
                    minBound(60, CoverageUnit.BRANCH)
                }
            }
        }
    }
}
