package com.chat.persistence.service

import com.chat.persistence.repository.PartitionedMessageRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@ConditionalOnProperty(
    prefix = "chat.message.store",
    name = ["target"],
    havingValue = "partitioned",
)
class PartitionedMessageWriteAdapter(
    private val partitionedMessageRepository: PartitionedMessageRepository,
    private val writeShardResolver: CanonicalWriteShardResolver,
) : MessageWritePort {

    @Transactional
    override fun write(requests: List<MessageWriteRequest>): MessageWriteResult {
        if (requests.isEmpty()) {
            return MessageWriteResult(emptyList())
        }

        val storageRequests = requests.map { request ->
            request.copy(
                writeShard = writeShardResolver.resolve(
                    roomId = request.chatRoomId,
                    messageId = request.messageId,
                ),
            )
        }
        val inserted = partitionedMessageRepository.batchInsert(storageRequests)
        if (inserted.size != storageRequests.size) {
            throw IllegalStateException(
                "PartitionedMessageRepository returned ${inserted.size} results for ${storageRequests.size} requests",
            )
        }

        return MessageWriteResult(
            outcomes = storageRequests.zip(inserted).map { (request, written) ->
                MessageWriteOutcome(request = request, written = written)
            },
        )
    }
}
