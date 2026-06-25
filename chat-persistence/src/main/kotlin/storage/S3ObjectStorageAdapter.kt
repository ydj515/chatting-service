package com.chat.persistence.storage

import com.chat.persistence.config.ChatObjectStorageProperties
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.time.Clock
import java.time.Duration

class S3ObjectStorageAdapter(
    private val s3Client: S3Client,
    private val presigner: S3Presigner,
    private val properties: ChatObjectStorageProperties,
    private val clock: Clock = Clock.systemUTC(),
) : ObjectStoragePort {
    override fun uploadFile(request: ObjectUploadRequest): ObjectUploadResult {
        val objectKey = normalizeObjectKey(request.objectKey)
        val putRequest = PutObjectRequest.builder()
            .bucket(properties.bucket)
            .key(objectKey)
            .contentType(request.contentType)
            .build()
        s3Client.putObject(putRequest, RequestBody.fromFile(request.file))
        return ObjectUploadResult(ObjectStorageUris.toS3Uri(properties.bucket, objectKey))
    }

    override fun createDownloadUrl(
        objectUri: String,
        ttl: Duration,
    ): PresignedObjectUrl {
        val location = ObjectStorageUris.parseS3Uri(objectUri)
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(location.bucket)
            .key(location.key)
            .build()
        val presignRequest = GetObjectPresignRequest.builder()
            .signatureDuration(ttl)
            .getObjectRequest(getObjectRequest)
            .build()
        val presigned = presigner.presignGetObject(presignRequest)
        return PresignedObjectUrl(
            url = presigned.url().toString(),
            expiresAt = clock.instant().plus(ttl),
        )
    }

    private fun normalizeObjectKey(objectKey: String): String {
        return objectKey.trim().trimStart('/').also {
            require(it.isNotBlank()) { "objectKey must not be blank" }
            require(!it.contains("..")) { "objectKey must not contain relative path segments" }
        }
    }
}
