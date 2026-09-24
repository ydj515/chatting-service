plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot.dependencies.get().toString())
    }
}

dependencies {
    implementation(project(":chat-domain"))
    implementation(libs.spring.data.commons)
    implementation(libs.spring.tx)
    implementation(libs.spring.context)
    implementation(libs.jackson.module.kotlin)
}
