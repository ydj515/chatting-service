package com.chat.core.message.service

import com.chat.core.message.command.SendMessageCommand
import com.chat.core.message.port.MessageAdmissionPolicyService
import com.chat.core.message.port.MessageModerationPolicyService
import com.chat.core.message.port.UserSanctionPolicyService
import com.chat.domain.model.MemberRole
import org.springframework.stereotype.Service

@Service
class MessageSendPolicy(
    private val userSanctionPolicyService: UserSanctionPolicyService,
    private val messageModerationPolicyService: MessageModerationPolicyService,
    private val messageAdmissionPolicyService: MessageAdmissionPolicyService,
) {
    fun requireAllowed(request: SendMessageCommand, senderId: Long, memberRole: MemberRole) {
        userSanctionPolicyService.requireAllowedToSend(
            roomId = request.chatRoomId,
            userId = senderId,
        )
        messageModerationPolicyService.requireAllowed(
            roomId = request.chatRoomId,
            senderId = senderId,
            content = request.content,
            messageType = request.type,
        )
        messageAdmissionPolicyService.requireAllowed(
            roomId = request.chatRoomId,
            senderId = senderId,
            memberRole = memberRole,
        )
    }
}
