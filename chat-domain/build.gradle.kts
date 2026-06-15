plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot.dependencies.get().toString())
    }
}

dependencies {
    // JPA 엔티티와 Bean Validation을 위한 필수 의존성
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)

    // 페이징을 위한 Spring Data Commons
    implementation(libs.spring.data.commons)

    // Jackson 어노테이션 사용 (WebSocketDto에서 사용)
    implementation(libs.jackson.module.kotlin)
}