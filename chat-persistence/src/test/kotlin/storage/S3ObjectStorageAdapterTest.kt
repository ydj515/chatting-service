package com.chat.persistence.storage

import com.chat.persistence.config.ChatObjectStorageProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest
import java.net.URI
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.writeText

class S3ObjectStorageAdapterTest {
    @Test
    fun `uploadFile uploads a local file and returns stable s3 uri`() {
        val s3Client = mock(S3Client::class.java)
        val presigner = mock(S3Presigner::class.java)
        val adapter = adapter(s3Client, presigner)
        val file = Files.createTempFile("export-", ".csv")
        file.writeText("id,message\n1,hello\n")

        val result = adapter.uploadFile(
            ObjectUploadRequest(
                objectKey = "admin-exports/export-1.csv",
                file = file,
                contentType = "text/csv; charset=utf-8",
            ),
        )

        assertEquals("s3://chat-archives/admin-exports/export-1.csv", result.objectUri)
        val requestCaptor = ArgumentCaptor.forClass(PutObjectRequest::class.java)
        val bodyCaptor = ArgumentCaptor.forClass(RequestBody::class.java)
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture())
        assertEquals("chat-archives", requestCaptor.value.bucket())
        assertEquals("admin-exports/export-1.csv", requestCaptor.value.key())
        assertEquals("text/csv; charset=utf-8", requestCaptor.value.contentType())
    }

    @Test
    fun `createDownloadUrl presigns a stable s3 uri with ttl`() {
        val s3Client = mock(S3Client::class.java)
        val presigner = mock(S3Presigner::class.java)
        val presigned = mock(PresignedGetObjectRequest::class.java)
        `when`(presigned.url()).thenReturn(
            URI.create("http://localhost:9000/chat-archives/admin-exports/export-1.csv?signature=abc").toURL(),
        )
        `when`(presigner.presignGetObject(org.mockito.ArgumentMatchers.any(GetObjectPresignRequest::class.java)))
            .thenReturn(presigned)
        val adapter = adapter(s3Client, presigner)

        val result = adapter.createDownloadUrl(
            objectUri = "s3://chat-archives/admin-exports/export-1.csv",
            ttl = Duration.ofMinutes(15),
        )

        assertEquals("http://localhost:9000/chat-archives/admin-exports/export-1.csv?signature=abc", result.url)
        assertEquals(Instant.parse("2026-06-26T00:15:00Z"), result.expiresAt)
        val requestCaptor = ArgumentCaptor.forClass(GetObjectPresignRequest::class.java)
        verify(presigner).presignGetObject(requestCaptor.capture())
        val getObjectRequest: GetObjectRequest = requestCaptor.value.getObjectRequest()
        assertEquals("chat-archives", getObjectRequest.bucket())
        assertEquals("admin-exports/export-1.csv", getObjectRequest.key())
        assertEquals(Duration.ofMinutes(15), requestCaptor.value.signatureDuration())
    }

    @Test
    fun `parseS3Uri rejects non s3 uri`() {
        val error = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            ObjectStorageUris.parseS3Uri("file:///tmp/export.csv")
        }

        assertTrue(error.message!!.contains("s3://"))
    }

    private fun adapter(
        s3Client: S3Client,
        presigner: S3Presigner,
    ): S3ObjectStorageAdapter {
        return S3ObjectStorageAdapter(
            s3Client = s3Client,
            presigner = presigner,
            properties = ChatObjectStorageProperties(
                bucket = "chat-archives",
            ),
            clock = Clock.fixed(Instant.parse("2026-06-26T00:00:00Z"), ZoneOffset.UTC),
        )
    }
}
