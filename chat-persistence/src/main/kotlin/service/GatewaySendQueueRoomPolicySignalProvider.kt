package com.chat.persistence.service

import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

/**
 * 현재 Gateway pending depth를 OVERLOAD 판정 신호로 노출한다.
 *
 * WebSocketSessionManager는 WebSocket 세션을 가진 프로세스에만 존재한다. RoomPolicyWorker가
 * 다른 실행 모듈에서 도는 배포에서는 같은 프로세스에 세션 매니저 bean이 없을 수 있으므로,
 * ObjectProvider로 주입받아 bean이 없으면 0을 반환한다(NoopRoomPolicySignalProvider 대비
 * @Primary로 우선 선택되되 충돌은 일으키지 않는다).
 *
 * writer/fanout lag는 Redis Streams group lag gauge를 입력으로 합성하는 후속 슬라이스에서 채운다.
 * 세션 매니저와 RoomPolicyWorker가 분리된 프로세스에서 send queue depth를 공유하는 cross-process
 * publish도 후속 슬라이스로 남긴다.
 */
@Service
@Primary
class GatewaySendQueueRoomPolicySignalProvider(
    private val sessionManagerProvider: ObjectProvider<WebSocketSessionManager>,
) : RoomPolicySignalProvider {
    override fun signals(roomId: Long): RoomPolicySignals {
        val depth = sessionManagerProvider.ifAvailable?.currentSendQueueDepth() ?: 0
        return RoomPolicySignals(gatewaySendQueueDepth = depth)
    }
}
