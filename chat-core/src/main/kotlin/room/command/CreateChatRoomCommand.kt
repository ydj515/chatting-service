package com.chat.core.room.command

import com.chat.domain.model.ChatRoomType

data class CreateChatRoomCommand(
    val name: String,
    val description: String?,
    val type: ChatRoomType,
    val imageUrl: String?,
    val maxMembers: Int = 100,
)
