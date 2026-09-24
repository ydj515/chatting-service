package com.chat.core.admin.port

/** Persist revocation in the sanction transaction and deliver it only after commit. */
interface SuspendedSessions {
    fun revokeAfterCommit(userId: Long)
}
