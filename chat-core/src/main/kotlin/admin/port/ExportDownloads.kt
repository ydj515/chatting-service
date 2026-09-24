package com.chat.core.admin.port

import java.time.Instant

interface ExportDownloads {
    /** Return null when downloads are disabled or temporarily unavailable. */
    fun createDownloadUrl(objectUri: String): ExportDownload?
}

data class ExportDownload(val url: String, val expiresAt: Instant)
