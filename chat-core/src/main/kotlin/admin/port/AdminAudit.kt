package com.chat.core.admin.port

interface AdminAudit {
    fun record(actor: String, action: String, targetType: String, targetId: String, metadata: Any)
}
