package com.chat.persistence.config

import com.chat.core.room.policy.RoomHeatClassifier
import com.chat.core.room.policy.RoomHeatLevel
import com.chat.core.room.policy.RoomHeatPolicy
import com.chat.core.room.policy.RoomHeatSettings
import com.chat.core.room.policy.RoomTrafficSnapshot
import com.chat.core.room.port.RoomPolicyRepository
import com.chat.core.room.port.RoomTrafficStatsService
import com.chat.core.room.service.RoomPolicyAutoDowngradeService
import com.chat.core.room.service.RoomPolicyWorker
import com.chat.persistence.service.GatewaySendQueueRoomPolicySignalProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.context.annotation.AnnotationConfigApplicationContext

class RoomPolicyConfigTest {
    @Test
    fun `existing flat configuration keys reach pure classification policy`() {
        val settings = Binder(
            MapConfigurationPropertySource(
                mapOf(
                    "chat.room-policy.hot-messages-per-second" to "5",
                    "chat.room-policy.very-hot-messages-per-second" to "10",
                    "chat.room-policy.hot-shard-count" to "3",
                    "chat.room-policy.very-hot-shard-count" to "7",
                    "chat.room-policy.overload-writer-lag-millis" to "123",
                    "chat.room-policy.overload-fanout-lag-millis" to "234",
                    "chat.room-policy.overload-gateway-queue-depth" to "17",
                    "chat.room-policy.normal-live-feed-max-messages" to "90",
                    "chat.room-policy.normal-live-feed-max-age-seconds" to "45",
                    "chat.room-policy.very-hot-live-feed-max-messages" to "70",
                    "chat.room-policy.very-hot-live-feed-max-age-seconds" to "35",
                    "chat.room-policy.overload-live-feed-max-messages" to "50",
                    "chat.room-policy.overload-live-feed-max-age-seconds" to "25",
                    "chat.room-policy.very-hot-room-rate-limit-per-second" to "100",
                    "chat.room-policy.overload-room-rate-limit-per-second" to "80",
                    "chat.room-policy.hot-slow-mode-seconds" to "2",
                    "chat.room-policy.very-hot-slow-mode-seconds" to "3",
                    "chat.room-policy.overload-slow-mode-seconds" to "4",
                ),
            ),
        ).bind("chat.room-policy", Bindable.of(ChatRoomPolicyProperties::class.java)).get()
        assertEquals(
            RoomHeatSettings(
                hotMessagesPerSecond = 5, veryHotMessagesPerSecond = 10, hotShardCount = 3, veryHotShardCount = 7,
                overloadWriterLagMillis = 123, overloadFanoutLagMillis = 234, overloadGatewayQueueDepth = 17,
                normalLiveFeedMaxMessages = 90, normalLiveFeedMaxAgeSeconds = 45,
                veryHotLiveFeedMaxMessages = 70, veryHotLiveFeedMaxAgeSeconds = 35,
                overloadLiveFeedMaxMessages = 50, overloadLiveFeedMaxAgeSeconds = 25,
                veryHotRoomRateLimitPerSecond = 100, overloadRoomRateLimitPerSecond = 80,
                hotSlowModeSeconds = 2, veryHotSlowModeSeconds = 3, overloadSlowModeSeconds = 4,
            ),
            settings.heatSettings(),
        )
        assertEquals(RoomHeatSettings(), ChatRoomPolicyProperties().heatSettings())
    }

    @Test
    fun `worker composes with configured classifier and required signal adapter`() {
        val traffic = mock(RoomTrafficStatsService::class.java)
        val applied = mutableListOf<RoomHeatPolicy>()
        val policies = object : RoomPolicyRepository {
            override fun applyAutomaticPolicy(policy: RoomHeatPolicy) {
                applied.add(policy)
            }
        }
        `when`(traffic.activeRoomIds()).thenReturn(setOf(10))
        `when`(traffic.snapshot(10)).thenReturn(RoomTrafficSnapshot(10, 10, 10))
        AnnotationConfigApplicationContext().use { context ->
            context.beanFactory.registerSingleton("traffic", traffic)
            context.beanFactory.registerSingleton("policies", policies)
            context.beanFactory.registerSingleton("properties", ChatRoomPolicyProperties(hotMessagesPerSecond = 5, veryHotMessagesPerSecond = 10))
            context.register(
                RoomPolicyConfig::class.java, GatewaySendQueueRoomPolicySignalProvider::class.java,
                RoomPolicyAutoDowngradeService::class.java, RoomPolicyWorker::class.java,
            )
            context.refresh()
            assertEquals(1, context.getBean(RoomPolicyWorker::class.java).pollAndApply())
            assertEquals(RoomHeatLevel.VERY_HOT, applied.single().heatLevel)
            assertEquals(RoomHeatLevel.VERY_HOT, context.getBean(RoomHeatClassifier::class.java).classify(RoomTrafficSnapshot(10, 10, 10)).heatLevel)
        }
    }
}
