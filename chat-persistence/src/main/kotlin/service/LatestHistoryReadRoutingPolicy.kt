package com.chat.persistence.service

interface LatestHistoryReadRoutingPolicy {
    fun usePrimaryForLatestHistory(): Boolean
}
