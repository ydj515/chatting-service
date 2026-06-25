package com.chat.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "chat.object-storage")
data class ChatObjectStorageProperties(
    val enabled: Boolean = false,
    // 앱 컨테이너가 업로드/조회에 사용하는 내부 S3 endpoint (예: Docker 네트워크의 http://minio:9000)
    val endpoint: String = "http://localhost:9000",
    // presigned download URL용 공개 endpoint. 브라우저가 직접 접근하므로 내부 endpoint와 분리한다.
    // 비어 있으면 endpoint로 폴백한다(단일 호스트/실제 AWS S3 환경).
    val publicEndpoint: String = "",
    val region: String = "us-east-1",
    val bucket: String = "chat-archives",
    val accessKey: String = "chatminio",
    val secretKey: String = "chatminiosecret",
    val pathStyleAccess: Boolean = true,
    val adminExportPrefix: String = "admin-exports",
    val presignedUrlTtl: Duration = Duration.ofMinutes(15),
) {
    // presigned URL은 호스트 브라우저가 직접 접근하므로 내부 endpoint가 아닌 공개 endpoint로 서명한다.
    // public-endpoint가 비어 있으면 내부 endpoint를 그대로 사용한다.
    fun resolvePresignEndpoint(): String = publicEndpoint.ifBlank { endpoint }
}
