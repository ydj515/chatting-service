package com.chat.worker.application

import com.chat.persistence.config.ChatWorkerProperties
import com.chat.persistence.service.AdminMessageExportWorker
import com.chat.persistence.service.HotRoomFanoutWorker
import com.chat.persistence.service.MessageWriterWorker
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class MessageWorkerSchedulerTest {

    @Test
    fun `message-writer role이 켜져 있으면 writer worker를 poll한다`() {
        val writerWorker = mock(MessageWriterWorker::class.java)
        val fanoutWorker = mock(HotRoomFanoutWorker::class.java)
        val exportWorker = mock(AdminMessageExportWorker::class.java)
        val scheduler = MessageWorkerScheduler(
            workerProperties = ChatWorkerProperties(roles = setOf("message-writer")),
            messageWriterWorker = writerWorker,
            hotRoomFanoutWorker = fanoutWorker,
            adminMessageExportWorker = exportWorker,
        )

        scheduler.pollWriter()

        verify(writerWorker).pollAndWrite()
    }

    @Test
    fun `message-writer role이 꺼져 있으면 writer worker를 poll하지 않는다`() {
        val writerWorker = mock(MessageWriterWorker::class.java)
        val fanoutWorker = mock(HotRoomFanoutWorker::class.java)
        val exportWorker = mock(AdminMessageExportWorker::class.java)
        val scheduler = MessageWorkerScheduler(
            workerProperties = ChatWorkerProperties(roles = setOf("fanout")),
            messageWriterWorker = writerWorker,
            hotRoomFanoutWorker = fanoutWorker,
            adminMessageExportWorker = exportWorker,
        )

        scheduler.pollWriter()

        verify(writerWorker, never()).pollAndWrite()
    }

    @Test
    fun `fanout role이 켜져 있으면 fanout worker를 poll한다`() {
        val writerWorker = mock(MessageWriterWorker::class.java)
        val fanoutWorker = mock(HotRoomFanoutWorker::class.java)
        val exportWorker = mock(AdminMessageExportWorker::class.java)
        val scheduler = MessageWorkerScheduler(
            workerProperties = ChatWorkerProperties(roles = setOf("fanout")),
            messageWriterWorker = writerWorker,
            hotRoomFanoutWorker = fanoutWorker,
            adminMessageExportWorker = exportWorker,
        )

        scheduler.pollFanout()

        verify(fanoutWorker).pollAndFanout()
    }

    @Test
    fun `admin-export role이 켜져 있으면 export worker를 poll한다`() {
        val writerWorker = mock(MessageWriterWorker::class.java)
        val fanoutWorker = mock(HotRoomFanoutWorker::class.java)
        val exportWorker = mock(AdminMessageExportWorker::class.java)
        val scheduler = MessageWorkerScheduler(
            workerProperties = ChatWorkerProperties(roles = setOf("admin-export")),
            messageWriterWorker = writerWorker,
            hotRoomFanoutWorker = fanoutWorker,
            adminMessageExportWorker = exportWorker,
        )

        scheduler.pollAdminExport()

        verify(exportWorker).pollAndExport()
    }
}
