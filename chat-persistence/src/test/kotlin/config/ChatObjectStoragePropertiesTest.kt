package com.chat.persistence.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChatObjectStoragePropertiesTest {
    @Test
    fun `public endpoint가 비어 있으면 내부 endpoint로 폴백한다`() {
        val properties = ChatObjectStorageProperties(
            endpoint = "http://minio:9000",
            publicEndpoint = "",
        )

        assertEquals("http://minio:9000", properties.resolvePresignEndpoint())
    }

    @Test
    fun `public endpoint가 설정되면 presign에는 공개 endpoint를 사용한다`() {
        val properties = ChatObjectStorageProperties(
            endpoint = "http://minio:9000",
            publicEndpoint = "http://127.0.0.1:9000",
        )

        // 업로드용 내부 endpoint와 presign용 공개 endpoint가 분리된다.
        assertEquals("http://minio:9000", properties.endpoint)
        assertEquals("http://127.0.0.1:9000", properties.resolvePresignEndpoint())
    }
}
