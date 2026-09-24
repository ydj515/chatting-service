package com.chat.api.dto

import com.chat.core.room.command.CreateChatRoomCommand
import com.chat.domain.model.ChatRoomType
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

data class CreateChatRoomRequest(
    // {"name": ""}
    @field:NotBlank(message = "채팅방 이름은 필수입니다")
    @field:Size(min = 1, max = 100, message = "채팅방 이름은 1-100자 사이여야 합니다")
    val name: String,
    val description: String?,
    // {"type" :null}
    @field:NotNull(message = "채팅방 타입은 필수입니다")
    val type: ChatRoomType,
    val imageUrl: String?,
    @field:Min(1)
    val maxMembers: Int = 100,
)

internal fun CreateChatRoomRequest.toCommand() = CreateChatRoomCommand(name, description, type, imageUrl, maxMembers)
