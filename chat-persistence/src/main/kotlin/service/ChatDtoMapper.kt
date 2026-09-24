package com.chat.persistence.service

import com.chat.core.dto.*
import com.chat.domain.model.*

internal fun Message.toMessageDto(): MessageDto {
    val roomSeq = if (this.roomSeq > 0) this.roomSeq else this.sequenceNumber
    return MessageDto(
        id = this.id,
        messageId = this.messageId ?: "legacy:${this.id}",
        clientMessageId = this.clientMessageId,
        chatRoomId = this.chatRoom.id,
        sender = this.sender.toUserDto(),
        type = this.type,
        content = this.content,
        isEdited = this.isEdited,
        isDeleted = this.isDeleted,
        createdAt = this.createdAt,
        editedAt = this.editedAt,
        sequenceNumber = this.sequenceNumber,
        roomSeq = roomSeq,
        streamShard = this.streamShard,
        writeShard = this.writeShard,
        fanoutShard = this.fanoutShard,
    )
}

internal fun User.toUserDto(): UserDto =
    UserDto(
        id = this.id,
        username = this.username,
        displayName = this.displayName,
        profileImageUrl = this.profileImageUrl,
        status = this.status,
        isActive = this.isActive,
        lastSeenAt = this.lastSeenAt,
        createdAt = this.createdAt,
    )
