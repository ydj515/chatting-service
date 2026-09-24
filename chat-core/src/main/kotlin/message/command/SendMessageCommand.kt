package com.chat.core.message.command

import com.chat.domain.model.MessageType

data class SendMessageCommand(
    val chatRoomId: Long,
    val type: MessageType,
    val content: String?,
    val clientMessageId: String? = null,
)
