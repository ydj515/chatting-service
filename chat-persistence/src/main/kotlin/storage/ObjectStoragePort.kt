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
            .joinToString("/") { segment -> encodePathSegment(segment) }
        return "s3://$bucket/$encodedKey"
    }

    fun parseS3Uri(objectUri: String): S3ObjectLocation {
        val uri = URI.create(objectUri)
        require(uri.scheme == "s3") { "Object URI must use s3:// scheme: $objectUri" }
        val bucket = uri.host.orEmpty()
        val key = uri.rawPath.removePrefix("/")
            .split("/")
            .joinToString("/") { segment -> decodePathSegment(segment) }
        require(bucket.isNotBlank()) { "Object URI bucket must not be blank: $objectUri" }
        require(key.isNotBlank()) { "Object URI key must not be blank: $objectUri" }
        return S3ObjectLocation(bucket = bucket, key = key)
    }

    // URLEncoder는 application/x-www-form-urlencoded 규격이라 공백을 '+'로 인코딩해
    // RFC 3986 path 규격과 어긋난다. 표준 S3 도구와의 round-trip을 위해 '+'를 '%20'으로 보정한다.
    private fun encodePathSegment(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

    // decode 전에 literal '+'를 '%2B'로 치환해 URLDecoder가 '+'를 공백으로 해석하지 않도록 한다.
    private fun decodePathSegment(segment: String): String =
        URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)
}
