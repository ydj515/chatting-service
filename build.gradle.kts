plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.kotlin.jpa) apply false
}

val rootLibs = libs

allprojects {
    group = "com.chat"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
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