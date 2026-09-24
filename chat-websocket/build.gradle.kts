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
    // 업무 타입과 애플리케이션 계약
    implementation(project(":chat-domain"))
    implementation(project(":chat-core"))
    implementation(project(":chat-protocol"))

    // WebSocket 기능
    implementation(libs.spring.boot.starter.websocket)

    // Gateway metrics
    implementation(libs.micrometer.core)

    // 페이징 지원 (채팅방 목록 로드용)
    implementation(libs.spring.data.commons)

    // JSON 직렬화/역직렬화 (WebSocket 메시지 처리용)
    implementation(libs.jackson.module.kotlin)
    testImplementation(libs.jackson.datatype.jsr310)
}
