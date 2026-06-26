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
) : MessageWritePort {

    @Transactional
    override fun write(requests: List<MessageWriteRequest>): MessageWriteResult {
        if (requests.isEmpty()) {
            return MessageWriteResult(emptyList())
        }

        // writeShard는 메시지 수락 시점(produce time)에 계산되어 envelope에 담긴 값을 그대로 사용한다.
        // canonical table의 PK가 write_shard를 포함하므로, 재계산하면 ack 유실 후 shard 확장 시 replay가
        // 다른 shard로 들어가 ON CONFLICT DO NOTHING을 우회해 같은 메시지의 중복 canonical row가 생긴다.
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
