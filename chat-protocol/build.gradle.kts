plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot.dependencies.get().toString())
    }
}

dependencies {
    implementation(project(":chat-domain"))
    implementation(libs.jackson.module.kotlin)
    testImplementation(libs.jackson.datatype.jsr310)
}
