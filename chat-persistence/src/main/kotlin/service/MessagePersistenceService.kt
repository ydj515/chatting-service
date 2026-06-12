package com.chat.persistence.service

import com.chat.domain.model.Message
import com.chat.persistence.repository.MessageRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class MessagePersistenceService(
    private val messageRepository: MessageRepository,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun save(message: Message): Message {
        return messageRepository.saveAndFlush(message)
    }
}
