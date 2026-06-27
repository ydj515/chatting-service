package com.chat.worker.application

import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.service.AdminMessageExportWorker
import com.chat.persistence.service.HotRoomFanoutWorker
import com.chat.persistence.service.MessageWriterWorker
import com.chat.persistence.service.RedisStreamLagMonitor
import com.chat.persistence.service.RoomPolicyWorker
import com.chat.persistence.service.RoomSeqGapAuditWorker
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class MessageWorkerSchedulerTest {

    @Test
    fun `message-writer role이 켜져 있으면 writer worker를 poll한다`() {
        val fixture = schedulerFixture(ChatWorkerProperties(roles = setOf("message-writer")))

        fixture.scheduler.pollWriter()

        verify(fixture.writerWorker).pollAndWrite()
    }

    @Test
    fun `message-writer role이 꺼져 있으면 writer worker를 poll하지 않는다`() {
        val fixture = schedulerFixture(ChatWorkerProperties(roles = setOf("fanout")))

        fixture.scheduler.pollWriter()

        verify(fixture.writerWorker, never()).pollAndWrite()
    }

    @Test
    fun `fanout role이 켜져 있으면 fanout worker를 poll한다`() {
        val fixture = schedulerFixture(ChatWorkerProperties(roles = setOf("fanout")))

        fixture.scheduler.pollFanout()

        verify(fixture.fanoutWorker).pollAndFanout()
    }

    @Test
    fun `admin-export role이 켜져 있으면 export worker를 poll한다`() {
        val fixture = schedulerFixture(ChatWorkerProperties(roles = setOf("admin-export")))

        fixture.scheduler.pollAdminExport()

        verify(fixture.exportWorker).pollAndExport()
    }

    @Test
    fun `room-policy role이 켜져 있으면 room policy worker를 poll한다`() {
        val fixture = schedulerFixture(ChatWorkerProperties(roles = setOf("room-policy")))

        fixture.scheduler.pollRoomPolicy()

        verify(fixture.roomPolicyWorker).pollAndApply()
    }

    @Test
    fun `room-policy role이 꺼져 있으면 room policy worker를 poll하지 않는다`() {
        val fixture = schedulerFixture(ChatWorkerProperties(roles = setOf("fanout")))

        fixture.scheduler.pollRoomPolicy()

        verify(fixture.roomPolicyWorker, never()).pollAndApply()
    }

    @Test
    fun `redis stream lag monitor가 켜져 있으면 direct lag gauge를 poll한다`() {
        val fixture = schedulerFixture(ChatWorkerProperties())

        fixture.scheduler.pollRedisStreamLag()

        verify(fixture.redisStreamLagMonitor).poll()
    }

    @Test
    fun `redis stream lag monitor가 꺼져 있으면 direct lag gauge를 poll하지 않는다`() {
        val fixture = schedulerFixture(
            ChatWorkerProperties(
                redisStreamLag = ChatWorkerProperties.RedisStreamLag(enabled = false),
            ),
        )

        fixture.scheduler.pollRedisStreamLag()

        verify(fixture.redisStreamLagMonitor, never()).poll()
    }

    @Test
    fun `roomSeq gap audit이 켜져 있으면 gap audit worker를 poll한다`() {
        val fixture = schedulerFixture(ChatWorkerProperties())

        fixture.scheduler.pollRoomSeqGapAudit()

        verify(fixture.roomSeqGapAuditWorker).poll()
    }

    @Test
    fun `roomSeq gap audit이 꺼져 있으면 gap audit worker를 poll하지 않는다`() {
        val fixture = schedulerFixture(
            ChatWorkerProperties(
                roomSeqGapAudit = ChatWorkerProperties.RoomSeqGapAudit(enabled = false),
            ),
        )

        fixture.scheduler.pollRoomSeqGapAudit()

        verify(fixture.roomSeqGapAuditWorker, never()).poll()
    }

    private fun schedulerFixture(workerProperties: ChatWorkerProperties): SchedulerFixture {
        val writerWorker = mock(MessageWriterWorker::class.java)
        val fanoutWorker = mock(HotRoomFanoutWorker::class.java)
        val exportWorker = mock(AdminMessageExportWorker::class.java)
        val roomPolicyWorker = mock(RoomPolicyWorker::class.java)
        val redisStreamLagMonitor = mock(RedisStreamLagMonitor::class.java)
        val roomSeqGapAuditWorker = mock(RoomSeqGapAuditWorker::class.java)
        val scheduler = MessageWorkerScheduler(
            workerProperties = workerProperties,
            messageWriterWorker = writerWorker,
            hotRoomFanoutWorker = fanoutWorker,
            adminMessageExportWorker = exportWorker,
            roomPolicyWorker = roomPolicyWorker,
            redisStreamLagMonitor = redisStreamLagMonitor,
            roomSeqGapAuditWorker = roomSeqGapAuditWorker,
        )

        return SchedulerFixture(
            scheduler = scheduler,
            writerWorker = writerWorker,
            fanoutWorker = fanoutWorker,
            exportWorker = exportWorker,
            roomPolicyWorker = roomPolicyWorker,
            redisStreamLagMonitor = redisStreamLagMonitor,
            roomSeqGapAuditWorker = roomSeqGapAuditWorker,
        )
    }

    private data class SchedulerFixture(
        val scheduler: MessageWorkerScheduler,
        val writerWorker: MessageWriterWorker,
        val fanoutWorker: HotRoomFanoutWorker,
        val exportWorker: AdminMessageExportWorker,
        val roomPolicyWorker: RoomPolicyWorker,
        val redisStreamLagMonitor: RedisStreamLagMonitor,
        val roomSeqGapAuditWorker: RoomSeqGapAuditWorker,
    )
}
