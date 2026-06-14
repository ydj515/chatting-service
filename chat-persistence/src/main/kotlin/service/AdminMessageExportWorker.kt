package com.chat.persistence.service

import com.chat.domain.dto.AdminExportMessagesRequest
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
) {
    private val logger = LoggerFactory.getLogger(AdminMessageExportWorker::class.java)

    fun pollAndExport(): Int {
        val job = exportJobRepository.claimNextPending(workerProperties.consumerName) ?: return 0
        return try {
            val request = objectMapper.readValue<AdminExportMessagesRequest>(job.requestJson)
            val messages = readMessages(request)
            val outputUri = writeCsv(job.jobId, messages)
            exportJobRepository.markCompleted(job.jobId, outputUri)
            logger.info("Completed admin message export job ${job.jobId} with ${messages.size} rows")
            messages.size
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            exportJobRepository.markFailed(job.jobId, reason)
            logger.warn("Failed admin message export job ${job.jobId}: $reason", e)
            0
        }
    }

    private fun readMessages(request: AdminExportMessagesRequest): List<AdminMessageDto> {
        val query = request.query?.trim().orEmpty()
        if (query.isNotEmpty()) {
            return messageRepository.searchMessages(
                query = query,
                searchMode = AdminMessageSearchMode.FTS,
                roomId = request.roomId,
                from = request.from,
                to = request.to,
                senderId = request.senderId,
                cursor = null,
                limit = EXPORT_MAX_ROWS,
            )
        }

        val roomId = request.roomId ?: error("roomId or query is required for admin message export")
        return messageRepository.findRoomMessages(
            roomId = roomId,
            from = request.from,
            to = request.to,
            cursor = null,
            limit = EXPORT_MAX_ROWS,
        )
    }

    private fun writeCsv(jobId: String, messages: List<AdminMessageDto>): String {
        val directory = Path.of(exportDirectory)
        Files.createDirectories(directory)
        val output = directory.resolve("$jobId.csv").toAbsolutePath().normalize()

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
            messages.forEach { message ->
                writer.appendLine(message.toCsvRow())
            }
        }

        return output.toUri().toString()
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
        const val EXPORT_MAX_ROWS = 10_000
    }
}
