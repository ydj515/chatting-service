package com.chat.worker.application

import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.service.AdminMessageExportWorker
import com.chat.persistence.service.HotRoomFanoutWorker
import com.chat.persistence.service.MessageWriterWorker
import com.chat.persistence.service.RoomPolicyWorker
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class MessageWorkerScheduler(
    private val workerProperties: ChatWorkerProperties,
    private val messageWriterWorker: MessageWriterWorker,
    private val hotRoomFanoutWorker: HotRoomFanoutWorker,
    private val adminMessageExportWorker: AdminMessageExportWorker,
    private val roomPolicyWorker: RoomPolicyWorker,
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

    @Scheduled(fixedDelayString = "\${chat.worker.room-policy.poll-delay-millis:1000}")
    fun pollRoomPolicy() {
        if (workerProperties.roleEnabled(ROLE_ROOM_POLICY)) {
            roomPolicyWorker.pollAndApply()
        }
    }

    private companion object {
        const val ROLE_MESSAGE_WRITER = "message-writer"
        const val ROLE_FANOUT = "fanout"
        const val ROLE_ADMIN_EXPORT = "admin-export"
        const val ROLE_ROOM_POLICY = "room-policy"
    }
}
