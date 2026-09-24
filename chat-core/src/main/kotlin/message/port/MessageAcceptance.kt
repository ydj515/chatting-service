package com.chat.core.message.port

import com.chat.core.dto.MessageDto
import com.chat.core.message.command.SendMessageCommand
import com.chat.domain.model.ChatRoom
import com.chat.domain.model.User

interface MessageAcceptance {
    fun findAccepted(roomId: Long, sender: User, clientMessageId: String): MessageDto?

    fun publish(request: SendMessageCommand, chatRoom: ChatRoom, sender: User): MessageDto
}
