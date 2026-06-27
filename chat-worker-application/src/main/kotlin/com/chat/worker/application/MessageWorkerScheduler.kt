package com.chat.worker.application

import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.service.AdminMessageExportWorker
import com.chat.persistence.service.HotRoomFanoutWorker
import com.chat.persistence.service.MessageWriterWorker
import com.chat.persistence.service.RedisStreamLagMonitor
import com.chat.persistence.service.RoomPolicyWorker
import com.chat.persistence.service.RoomSeqGapAuditWorker
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class MessageWorkerScheduler(
    private val workerProperties: ChatWorkerProperties,
    private val messageWriterWorker: MessageWriterWorker,
    private val hotRoomFanoutWorker: HotRoomFanoutWorker,
    private val adminMessageExportWorker: AdminMessageExportWorker,
    private val roomPolicyWorker: RoomPolicyWorker,
    private val redisStreamLagMonitor: RedisStreamLagMonitor,
    private val roomSeqGapAuditWorker: RoomSeqGapAuditWorker,
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

    @Scheduled(fixedDelayString = "\${chat.worker.redis-stream-lag.poll-delay-millis:5000}")
    fun pollRedisStreamLag() {
        if (workerProperties.redisStreamLag.enabled) {
            redisStreamLagMonitor.poll()
        }
    }

    @Scheduled(fixedDelayString = "\${chat.worker.room-seq-gap-audit.poll-delay-millis:60000}")
    fun pollRoomSeqGapAudit() {
        if (workerProperties.roleEnabled(ROLE_ROOM_SEQ_GAP_AUDIT) && workerProperties.roomSeqGapAudit.enabled) {
            roomSeqGapAuditWorker.poll()
        }
    }

    private companion object {
        const val ROLE_MESSAGE_WRITER = "message-writer"
        const val ROLE_FANOUT = "fanout"
        const val ROLE_ADMIN_EXPORT = "admin-export"
        const val ROLE_ROOM_POLICY = "room-policy"
        const val ROLE_ROOM_SEQ_GAP_AUDIT = "room-seq-gap-audit"
    }
}
