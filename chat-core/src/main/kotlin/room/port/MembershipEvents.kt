package com.chat.core.room.port

/** Publish committed membership changes; never notify subscribers about a rolled-back change. */
interface MembershipEvents {
    fun joinedAfterCommit(userId: Long, roomId: Long)

    fun leftAfterCommit(userId: Long, roomId: Long)
}
