package com.chat.core.message.port

import com.chat.domain.model.MemberRole

interface MessageAdmissionPolicyService {
    fun requireAllowed(roomId: Long, senderId: Long, memberRole: MemberRole = MemberRole.MEMBER)

    object Noop : MessageAdmissionPolicyService {
        override fun requireAllowed(roomId: Long, senderId: Long, memberRole: MemberRole) = Unit
    }
}
