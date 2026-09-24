package com.chat.core.message.service

import com.chat.core.dto.MessageDto
import com.chat.core.message.command.SendMessageCommand
import com.chat.core.message.port.MessageAcceptance
import com.chat.core.message.port.MessageReadPort
import com.chat.core.room.port.ChatMembershipStore
import com.chat.core.room.port.ChatRoomStore
import com.chat.core.user.port.UserStore
import com.chat.domain.exception.ForbiddenOperationException
import com.chat.domain.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class MessageSendingService(
    private val chatRoomRepository: ChatRoomStore,
    private val userRepository: UserStore,
    private val chatRoomMemberRepository: ChatMembershipStore,
    private val messageReadPort: MessageReadPort,
    private val messageSendPolicy: MessageSendPolicy,
    private val messageStreamPublisher: MessageAcceptance,
) {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun sendMessage(
        request: SendMessageCommand,
        senderId: Long,
    ): MessageDto {
        val rawClientMessageId = request.clientMessageId
        require(rawClientMessageId == null || rawClientMessageId.length <= 128) { "clientMessageId must be at most 128 characters" }
        val requestedClientMessageId = request.clientMessageId?.trim()?.takeIf { it.isNotEmpty() }
        val chatRoom = chatRoomRepository.findById(request.chatRoomId)
            ?: throw ResourceNotFoundException("채팅방을 찾을 수 없습니다: ${request.chatRoomId}")

        val sender = userRepository.findById(senderId)
            ?: throw ResourceNotFoundException("사용자를 찾을 수 없습니다: $senderId")

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
            messageStreamPublisher.findAccepted(request.chatRoomId, sender, requestedClientMessageId)?.let { return it }
        }

        messageSendPolicy.requireAllowed(request, senderId, member.role)
        return messageStreamPublisher.publish(request, chatRoom, sender)
    }
}
