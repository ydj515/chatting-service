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
    // 도메인 모듈 의존성
    implementation(project(":chat-domain"))

    // 데이터 접근을 위한 JPA
    implementation(libs.spring.boot.starter.data.jpa)

    // Redis 캐시 및 Pub/Sub
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.cache)

    // WebSocket (WebSocketSessionManager에서 사용)
    implementation(libs.spring.boot.starter.websocket)

    // Jackson (Redis 직렬화 및 WebSocket 메시지 처리용)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.aws.sdk.s3)
    implementation(libs.micrometer.core)
    implementation(libs.micrometer.registry.prometheus)

    // 데이터베이스 드라이버 (런타임)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.postgresql)
}
