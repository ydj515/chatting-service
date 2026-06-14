package com.chat.worker.application

import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.service.AdminMessageExportWorker
import com.chat.persistence.service.HotRoomFanoutWorker
import com.chat.persistence.service.MessageWriterWorker
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class MessageWorkerScheduler(
    private val workerProperties: ChatWorkerProperties,
    private val messageWriterWorker: MessageWriterWorker,
    private val hotRoomFanoutWorker: HotRoomFanoutWorker,
    private val adminMessageExportWorker: AdminMessageExportWorker,
) {

    @Scheduled(fixedDelayString = "\${chat.worker.poll-delay-millis:100}")
    fun pollWriter() {
        if (workerProperties.roleEnabled(ROLE_MESSAGE_WRITER)) {
            messageWriterWorker.pollAndWrite()
        }
    }

    @Scheduled(fixedDelayString = "\${chat.worker.poll-delay-millis:100}")
    fun pollFanout() {
        if (workerProperties.roleEnabled(ROLE_FANOUT)) {
            hotRoomFanoutWorker.pollAndFanout()
        }
    }

    @Scheduled(fixedDelayString = "\${chat.worker.poll-delay-millis:1000}")
    fun pollAdminExport() {
        if (workerProperties.roleEnabled(ROLE_ADMIN_EXPORT)) {
            adminMessageExportWorker.pollAndExport()
        }
    }

    private companion object {
        const val ROLE_MESSAGE_WRITER = "message-writer"
        const val ROLE_FANOUT = "fanout"
        const val ROLE_ADMIN_EXPORT = "admin-export"
    }
}
