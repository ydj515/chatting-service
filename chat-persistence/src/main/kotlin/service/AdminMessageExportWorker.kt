package com.chat.persistence.service

import com.chat.domain.dto.AdminExportMessagesRequest
import com.chat.domain.dto.AdminMessageCursor
import com.chat.domain.dto.AdminMessageDto
import com.chat.domain.dto.AdminMessageSearchMode
import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.repository.AdminExportJobRepository
import com.chat.persistence.repository.AdminMessageRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

@Service
class AdminMessageExportWorker(
    private val exportJobRepository: AdminExportJobRepository,
    private val messageRepository: AdminMessageRepository,
    private val workerProperties: ChatWorkerProperties,
    private val objectMapper: ObjectMapper,
    @Value("\${chat.admin.export.directory:build/admin-exports}")
    private val exportDirectory: String,
    @Value("\${chat.admin.export.chunk-size:1000}")
    private val exportChunkSize: Int = DEFAULT_EXPORT_CHUNK_SIZE,
) {
    private val logger = LoggerFactory.getLogger(AdminMessageExportWorker::class.java)

    fun pollAndExport(): Int {
        val job = exportJobRepository.claimNextPending(workerProperties.consumerName) ?: return 0
        return try {
            val request = objectMapper.readValue<AdminExportMessagesRequest>(job.requestJson)
            val exportResult = writeCsv(job.jobId, request)
            val outputUri = exportResult.outputUri
            exportJobRepository.markCompleted(job.jobId, outputUri)
            logger.info("Completed admin message export job ${job.jobId} with ${exportResult.exportedRows} rows")
            exportResult.exportedRows
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            exportJobRepository.markFailed(job.jobId, reason)
            logger.warn("Failed admin message export job ${job.jobId}: $reason", e)
            0
        }
    }

    private fun readMessagesChunk(
        request: AdminExportMessagesRequest,
        cursor: AdminMessageCursor?,
        limit: Int,
    ): List<AdminMessageDto> {
        val query = request.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            return messageRepository.searchMessages(
                query = query,
                searchMode = AdminMessageSearchMode.FTS,
                roomId = request.roomId,
                from = request.from,
                to = request.to,
                senderId = request.senderId,
                cursor = cursor,
                limit = limit,
            )
        }

        val roomId = request.roomId ?: error("roomId or query is required for admin message export")
        return messageRepository.findRoomMessages(
            roomId = roomId,
            from = request.from,
            to = request.to,
            cursor = cursor,
            limit = limit,
        )
    }

    private fun writeCsv(jobId: String, request: AdminExportMessagesRequest): ExportResult {
        val directory = Path.of(exportDirectory)
        Files.createDirectories(directory)
        val output = directory.resolve("$jobId.csv").toAbsolutePath().normalize()
        var exportedRows = 0

        Files.newBufferedWriter(output, StandardCharsets.UTF_8).use { writer ->
            writer.appendLine(
                listOf(
                    "messageId",
                    "clientMessageId",
                    "roomId",
                    "roomSeq",
                    "writeShard",
                    "senderId",
                    "senderUsername",
                    "senderDisplayName",
                    "messageType",
                    "content",
                    "isDeleted",
                    "createdAt",
                ).joinToString(","),
            )
            var cursor: AdminMessageCursor? = null
            val chunkLimit = exportChunkSize.coerceIn(1, EXPORT_MAX_ROWS)

            while (exportedRows < EXPORT_MAX_ROWS) {
                val limit = minOf(chunkLimit, EXPORT_MAX_ROWS - exportedRows)
                val chunk = readMessagesChunk(request, cursor, limit)
                if (chunk.isEmpty()) {
                    break
                }

                chunk.forEach { message ->
                    writer.appendLine(message.toCsvRow())
                }
                exportedRows += chunk.size
                cursor = chunk.last().toExportCursor()

                if (chunk.size < limit) {
                    break
                }
            }
        }

        return ExportResult(
            outputUri = output.toUri().toString(),
            exportedRows = exportedRows,
        )
    }

    private fun AdminMessageDto.toExportCursor(): AdminMessageCursor {
        return AdminMessageCursor(
            createdAt = createdAt,
            roomSeq = roomSeq,
            messageId = messageId,
        )
    }

    private fun AdminMessageDto.toCsvRow(): String {
        return listOf(
            messageId,
            clientMessageId.orEmpty(),
            roomId.toString(),
            roomSeq.toString(),
            writeShard.toString(),
            senderId.toString(),
            senderUsername,
            senderDisplayName,
            messageType.name,
            content.orEmpty(),
            isDeleted.toString(),
            createdAt.toString(),
        ).joinToString(",") { it.csvEscape() }
    }

    private fun String.csvEscape(): String {
        val formulaSafe = if (startsWithSpreadsheetFormulaPrefix()) "'$this" else this
        val needsQuoting = formulaSafe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = formulaSafe.replace("\"", "\"\"")
        return if (needsQuoting) "\"$escaped\"" else escaped
    }

    private fun String.startsWithSpreadsheetFormulaPrefix(): Boolean {
        return firstOrNull() in setOf('=', '+', '-', '@')
    }

    private companion object {
        const val DEFAULT_EXPORT_CHUNK_SIZE = 1_000
        const val EXPORT_MAX_ROWS = 10_000
    }

    private data class ExportResult(
        val outputUri: String,
        val exportedRows: Int,
    )
}
