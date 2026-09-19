package com.chat.persistence.service

import com.chat.domain.dto.MessageDto
import com.chat.domain.dto.SendMessageRequest
import com.chat.domain.exception.ForbiddenOperationException
import com.chat.domain.exception.ResourceNotFoundException
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.chat.persistence.repository.ChatRoomRepository
import com.chat.persistence.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class MessageSendingService(
    private val chatRoomRepository: ChatRoomRepository,
    private val userRepository: UserRepository,
    private val chatRoomMemberRepository: ChatRoomMemberRepository,
    private val messageReadPort: MessageReadPort,
    private val messageSendPolicy: MessageSendPolicy,
    private val messageStreamPublisher: MessageStreamPublisher,
) {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun sendMessage(
        request: SendMessageRequest,
        senderId: Long,
    ): MessageDto {
        val rawClientMessageId = request.clientMessageId
        require(rawClientMessageId == null || rawClientMessageId.length <= 128) { "clientMessageId must be at most 128 characters" }
        val requestedClientMessageId = request.clientMessageId?.trim()?.takeIf { it.isNotEmpty() }
        val chatRoom = chatRoomRepository.findById(request.chatRoomId)
            .orElseThrow { ResourceNotFoundException("채팅방을 찾을 수 없습니다: ${request.chatRoomId}") }

        val sender = userRepository.findById(senderId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다: $senderId") }

        val member = chatRoomMemberRepository.findByChatRoomIdAndUserIdAndIsActiveTrue(request.chatRoomId, senderId)
            ?: throw ForbiddenOperationException("채팅방에 참여하지 않은 사용자입니다.")

        if (requestedClientMessageId != null) {
            val existingMessage = messageReadPort.findByClientMessageId(
                roomId = request.chatRoomId,
                senderId = senderId,
                clientMessageId = requestedClientMessageId,
            )
            if (existingMessage != null) {
                return existingMessage
            }
        }

        messageSendPolicy.requireAllowed(request, senderId, member.role)
        return messageStreamPublisher.publish(request, chatRoom, sender)
    }
}
