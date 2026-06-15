plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot.dependencies.get().toString())
    }
}

dependencies {
    implementation(project(":chat-runtime-config"))
    implementation(project(":chat-api"))
    implementation(project(":chat-domain"))
    implementation(project(":chat-persistence"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.jpa)

    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)
}
