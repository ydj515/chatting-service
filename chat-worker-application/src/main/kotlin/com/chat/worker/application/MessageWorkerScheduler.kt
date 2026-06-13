package com.chat.worker.application

import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.service.MessageWriterWorker
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class MessageWorkerScheduler(
    private val workerProperties: ChatWorkerProperties,
    private val messageWriterWorker: MessageWriterWorker,
) {

    @Scheduled(fixedDelayString = "\${chat.worker.poll-delay-millis:100}")
    fun pollWriter() {
        if (workerProperties.roleEnabled(ROLE_MESSAGE_WRITER)) {
            messageWriterWorker.pollAndWrite()
        }
    }

    private companion object {
        const val ROLE_MESSAGE_WRITER = "message-writer"
    }
}
