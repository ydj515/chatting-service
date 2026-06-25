package com.chat.persistence.config

import com.chat.persistence.storage.ObjectStoragePort
import com.chat.persistence.storage.ObjectUploadRequest
import com.chat.persistence.storage.ObjectUploadResult
import com.chat.persistence.storage.PresignedObjectUrl
import com.chat.persistence.storage.S3ObjectStorageAdapter
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI
import java.time.Duration

@Configuration
class ObjectStorageConfig {
    @Bean
    @ConditionalOnProperty(prefix = "chat.object-storage", name = ["enabled"], havingValue = "true")
    fun s3Client(properties: ChatObjectStorageProperties): S3Client {
        val builder = S3Client.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentials(properties))
            .serviceConfiguration(s3Configuration(properties))
        properties.endpoint.takeIf { it.isNotBlank() }?.let { builder.endpointOverride(URI.create(it)) }
        return builder.build()
    }

    @Bean
    @ConditionalOnProperty(prefix = "chat.object-storage", name = ["enabled"], havingValue = "true")
    fun s3Presigner(properties: ChatObjectStorageProperties): S3Presigner {
        val builder = S3Presigner.builder()
            .region(Region.of(properties.region))
            .credentialsProvider(credentials(properties))
            .serviceConfiguration(s3Configuration(properties))
        // 업로드용 s3Client는 내부 endpoint를 쓰지만, presigner는 브라우저가 접근할 공개 endpoint로 서명한다.
        properties.resolvePresignEndpoint().takeIf { it.isNotBlank() }?.let { builder.endpointOverride(URI.create(it)) }
        return builder.build()
    }

    @Bean
    @ConditionalOnProperty(prefix = "chat.object-storage", name = ["enabled"], havingValue = "true")
    fun objectStoragePort(
        s3Client: S3Client,
        s3Presigner: S3Presigner,
        properties: ChatObjectStorageProperties,
    ): ObjectStoragePort {
        return S3ObjectStorageAdapter(s3Client, s3Presigner, properties)
    }

    @Bean
    @ConditionalOnMissingBean(ObjectStoragePort::class)
    fun disabledObjectStoragePort(): ObjectStoragePort {
        return object : ObjectStoragePort {
            override fun uploadFile(request: ObjectUploadRequest): ObjectUploadResult {
                error("Object Storage is disabled")
            }

            override fun createDownloadUrl(
                objectUri: String,
                ttl: Duration,
            ): PresignedObjectUrl {
                error("Object Storage is disabled")
            }
        }
    }

    private fun credentials(properties: ChatObjectStorageProperties): StaticCredentialsProvider {
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(properties.accessKey, properties.secretKey),
        )
    }

    private fun s3Configuration(properties: ChatObjectStorageProperties): S3Configuration {
        return S3Configuration.builder()
            .pathStyleAccessEnabled(properties.pathStyleAccess)
            .build()
    }
}
