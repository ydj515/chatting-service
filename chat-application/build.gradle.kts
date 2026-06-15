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
    // 모든 하위 모듈 포함 (조립자 역할)
    implementation(project(":chat-runtime-config"))
    implementation(project(":chat-api"))
    implementation(project(":chat-domain"))
    implementation(project(":chat-persistence"))
    implementation(project(":chat-websocket"))

    // 메인 애플리케이션 실행에 필요한 의존성만
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.actuator)

    // JPA 어노테이션 사용을 위해 필요 (@EnableJpaRepositories, @EnableJpaAuditing, @EntityScan)
    implementation(libs.spring.boot.starter.data.jpa)

    // 데이터베이스 (런타임에만 필요)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)
}
