package com.chat.persistence.storage

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

interface ObjectStoragePort {
    fun uploadFile(request: ObjectUploadRequest): ObjectUploadResult

    fun createDownloadUrl(
        objectUri: String,
        ttl: Duration,
    ): PresignedObjectUrl
}

data class ObjectUploadRequest(
    val objectKey: String,
    val file: Path,
    val contentType: String,
)

data class ObjectUploadResult(
    val objectUri: String,
)

data class PresignedObjectUrl(
    val url: String,
    val expiresAt: Instant,
)

data class S3ObjectLocation(
    val bucket: String,
    val key: String,
)

object ObjectStorageUris {
    fun toS3Uri(
        bucket: String,
        key: String,
    ): String {
        require(bucket.isNotBlank()) { "bucket must not be blank" }
        require(key.isNotBlank()) { "key must not be blank" }
        val encodedKey = key.split("/")
            .joinToString("/") { segment -> URLEncoder.encode(segment, StandardCharsets.UTF_8) }
        return "s3://$bucket/$encodedKey"
    }

    fun parseS3Uri(objectUri: String): S3ObjectLocation {
        val uri = URI.create(objectUri)
        require(uri.scheme == "s3") { "Object URI must use s3:// scheme: $objectUri" }
        val bucket = uri.host.orEmpty()
        val key = uri.rawPath.removePrefix("/")
            .split("/")
            .joinToString("/") { segment -> URLDecoder.decode(segment, StandardCharsets.UTF_8) }
        require(bucket.isNotBlank()) { "Object URI bucket must not be blank: $objectUri" }
        require(key.isNotBlank()) { "Object URI key must not be blank: $objectUri" }
        return S3ObjectLocation(bucket = bucket, key = key)
    }
}
