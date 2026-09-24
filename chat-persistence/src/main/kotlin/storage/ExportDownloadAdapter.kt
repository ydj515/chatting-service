package com.chat.persistence.storage

import com.chat.core.admin.port.ExportDownload
import com.chat.core.admin.port.ExportDownloads
import com.chat.persistence.config.ChatObjectStorageProperties
import org.springframework.stereotype.Component

@Component
class ExportDownloadAdapter(private val storage: ObjectStoragePort, private val properties: ChatObjectStorageProperties) : ExportDownloads {
    override fun createDownloadUrl(objectUri: String): ExportDownload? {
        if (!properties.enabled) return null
        return runCatching {
            val signed = storage.createDownloadUrl(objectUri, properties.presignedUrlTtl)
            ExportDownload(signed.url, signed.expiresAt)
        }.getOrNull()
    }
}
