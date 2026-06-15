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
    // 도메인 모듈 의존성 (DTO와 서비스 인터페이스 사용)
    implementation(project(":chat-domain"))

    // REST API 기능
    implementation(libs.spring.boot.starter.web)

    // 입력 데이터 검증
    implementation(libs.spring.boot.starter.validation)

    // 페이징 지원
    implementation(libs.spring.data.commons)

    implementation(libs.spring.boot.starter.actuator)
}