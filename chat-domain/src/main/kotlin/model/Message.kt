package com.chat.domain.model

import jakarta.persistence.*
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

@Entity
@Table(
    name = "messages",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_message_message_id", columnNames = ["message_id"]),
        UniqueConstraint(name = "uk_message_client_dedupe", columnNames = ["chat_room_id", "sender_id", "client_message_id"])
    ],
    indexes = [
        Index(name = "idx_message_message_id", columnList = "message_id"),
        Index(name = "idx_message_chat_room_id", columnList = "chat_room_id"),
        Index(name = "idx_message_sender_id", columnList = "sender_id"),
        Index(name = "idx_message_created_at", columnList = "created_at"),
        Index(name = "idx_message_room_time", columnList = "chat_room_id,created_at"),
        Index(name = "idx_message_room_sequence", columnList = "chat_room_id,sequence_number"),
        Index(name = "idx_message_room_seq", columnList = "chat_room_id,room_seq")
    ]
)
@EntityListeners(AuditingEntityListener::class)
data class Message(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "message_id", length = 64)
    val messageId: String? = null,

    @Column(name = "client_message_id", length = 128)
    val clientMessageId: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_room_id", nullable = false)
    val chatRoom: ChatRoom,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", nullable = false)
    val sender: User,

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    val type: MessageType = MessageType.TEXT,

    @Column(columnDefinition = "TEXT")
    val content: String? = null,

    @Column(nullable = false)
    val isEdited: Boolean = false,

    @Column(nullable = false)
    val isDeleted: Boolean = false,

    @Column(nullable = false)
    val sequenceNumber: Long = 0,

    @Column(name = "room_seq", nullable = false, columnDefinition = "bigint default 0")
    val roomSeq: Long = 0,

    @Column(name = "stream_shard", nullable = false, columnDefinition = "integer default 0")
    val streamShard: Int = 0,

    @Column(name = "write_shard", nullable = false, columnDefinition = "integer default 0")
    val writeShard: Int = 0,

    @Column(name = "fanout_shard", nullable = false, columnDefinition = "integer default 0")
    val fanoutShard: Int = 0,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column
    val editedAt: LocalDateTime? = null
)

enum class MessageType {
    TEXT,
    SYSTEM
}
