package com.chat.persistence.service

import com.chat.core.gateway.port.LocalGateway
import com.chat.core.room.port.RoomPolicySignalProvider
import com.chat.core.room.port.RoomPolicySignals
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service

/**
 * 현재 Gateway pending depth를 OVERLOAD 판정 신호로 노출한다.
 *
 * LocalGateway는 WebSocket 세션을 가진 프로세스에만 존재한다. RoomPolicyWorker가
 * 다른 실행 모듈에서 도는 배포에서는 같은 프로세스에 세션 매니저 bean이 없을 수 있으므로,
 * ObjectProvider로 주입받아 bean이 없으면 0을 반환한다. 이 프로세스의 로컬 연결 부하만 관측한다.
 *
 * writer/fanout lag는 Redis Streams group lag gauge를 입력으로 합성하는 후속 슬라이스에서 채운다.
 * 세션 매니저와 RoomPolicyWorker가 분리된 프로세스에서 send queue depth를 공유하는 cross-process
 * publish도 후속 슬라이스로 남긴다.
 */
@Service
class GatewaySendQueueRoomPolicySignalProvider(
    private val sessionManagerProvider: ObjectProvider<LocalGateway>,
) : RoomPolicySignalProvider {
    override fun signals(roomId: Long): RoomPolicySignals {
        val depth = sessionManagerProvider.ifAvailable?.currentSendQueueDepth() ?: 0
        return RoomPolicySignals(gatewaySendQueueDepth = depth)
    }
}
