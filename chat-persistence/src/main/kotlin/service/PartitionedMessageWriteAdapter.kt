package com.chat.persistence.service

import com.chat.persistence.repository.PartitionedMessageRepository
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

@Service
@ConditionalOnProperty(
    prefix = "chat.message.store",
    name = ["target"],
    havingValue = "partitioned",
)
class PartitionedMessageWriteAdapter(
    private val partitionedMessageRepository: PartitionedMessageRepository,
) : MessageWritePort {

    override fun write(requests: List<MessageWriteRequest>): MessageWriteResult {
        if (requests.isEmpty()) {
            return MessageWriteResult(emptyList())
        }

        val inserted = partitionedMessageRepository.batchInsert(requests)
        if (inserted.size != requests.size) {
            throw IllegalStateException(
                "PartitionedMessageRepository returned ${inserted.size} results for ${requests.size} requests",
            )
        }

        return MessageWriteResult(
            outcomes = requests.zip(inserted).map { (request, written) ->
                MessageWriteOutcome(request = request, written = written)
            },
        )
    }
}
