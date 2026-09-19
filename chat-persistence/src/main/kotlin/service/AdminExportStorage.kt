package com.chat.persistence.service

import com.chat.persistence.config.ChatObjectStorageProperties
import com.chat.persistence.storage.ObjectStoragePort
import com.chat.persistence.storage.ObjectUploadRequest
import com.chat.persistence.storage.ObjectUploadResult
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class AdminExportStorage(
    private val objectStoragePort: ObjectStoragePort,
    private val properties: ChatObjectStorageProperties,
) {
    fun upload(jobId: String, file: Path): ObjectUploadResult = objectStoragePort.uploadFile(
        ObjectUploadRequest(objectKey = objectKey(jobId), file = file, contentType = "text/csv; charset=utf-8"),
    )

    private fun objectKey(jobId: String): String {
        val safeJobId = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val prefix = properties.adminExportPrefix.trim().trim('/')
        return if (prefix.isBlank()) "$safeJobId.csv" else "$prefix/$safeJobId.csv"
    }
}
