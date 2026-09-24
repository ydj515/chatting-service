package com.chat.core.admin.port

import com.chat.core.dto.ModerationScopeType

/** Register durable invalidation in the current sanction transaction. */
interface SanctionCacheInvalidation {
    fun enqueue(scopeType: ModerationScopeType, roomId: Long?, userId: Long)
}
