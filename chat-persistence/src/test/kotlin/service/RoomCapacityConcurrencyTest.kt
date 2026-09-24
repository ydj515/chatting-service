package com.chat.persistence.service

import com.chat.core.dto.CreateChatRoomRequest
import com.chat.core.message.port.MessageReadPort
import com.chat.core.message.service.MessageSendingService
import com.chat.core.room.service.ChatServiceImpl
import com.chat.domain.exception.ResourceConflictException
import com.chat.domain.model.ChatRoom
import com.chat.domain.model.ChatRoomMember
import com.chat.domain.model.ChatRoomType
import com.chat.domain.model.User
import com.chat.persistence.repository.ChatMembershipStoreAdapter
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.chat.persistence.repository.ChatRoomRepository
import com.chat.persistence.repository.ChatRoomStoreAdapter
import com.chat.persistence.repository.UserRepository
import com.chat.persistence.repository.UserStoreAdapter
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJpaTest(properties = ["spring.datasource.generate-unique-name=true", "spring.jpa.hibernate.ddl-auto=create-drop"])
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = [ChatServiceImplCursorPaginationTest.JpaTestConfig::class])
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RoomCapacityConcurrencyTest {
    @Autowired private lateinit var rooms: ChatRoomRepository

    @Autowired private lateinit var users: UserRepository

    @Autowired private lateinit var members: ChatRoomMemberRepository

    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `only one concurrent join can occupy the last room slot`() {
        val transaction = TransactionTemplate(transactionManager)
        val state = requireNotNull(
            transaction.execute { _ ->
                val people = (1..3).map { users.save(User(username = UUID.randomUUID().toString(), password = "test", displayName = "test")) }
                val room = rooms.save(ChatRoom(name = "capacity", maxMembers = 2, createdBy = people.first()))
                members.save(ChatRoomMember(chatRoom = room, user = people.first()))
                room.id to people.drop(1).map { it.id }
            },
        )
        val service = service()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = state.second.map { userId ->
                executor.submit<Boolean> {
                    check(start.await(5, TimeUnit.SECONDS))
                    try {
                        transaction.executeWithoutResult { service.joinChatRoom(state.first, userId) }
                        true
                    } catch (expected: ResourceConflictException) {
                        false
                    }
                }
            }
            start.countDown()
            assertEquals(1, attempts.count { it.get(10, TimeUnit.SECONDS) })
            assertEquals(2, members.countActiveMembersInRoom(state.first))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `non HTTP room creation cannot bypass positive capacity validation`() {
        assertThrows(IllegalArgumentException::class.java) {
            service().createChatRoom(CreateChatRoomRequest("room", null, ChatRoomType.GROUP, null, 0), 1)
        }
    }

    private fun service() = ChatServiceImpl(ChatRoomStoreAdapter(rooms), mock(MessageReadPort::class.java), ChatMembershipStoreAdapter(members), UserStoreAdapter(users), mock(MessageSendingService::class.java), mock(MembershipEventPublisher::class.java))

    companion object {
        @JvmStatic
        @org.springframework.test.context.DynamicPropertySource
        fun database(registry: org.springframework.test.context.DynamicPropertyRegistry) {
            val url = System.getenv("CHAT_TEST_POSTGRES_URL") ?: return
            val password = System.getenv("CHAT_TEST_POSTGRES_PASSWORD").orEmpty()
            val schema = "capacity_${UUID.randomUUID().toString().replace("-", "") }"
            java.sql.DriverManager.getConnection(url, "postgres", password).use { connection ->
                connection.createStatement().use { statement -> statement.execute("CREATE SCHEMA $schema") }
            }
            registry.add("spring.datasource.url") { "$url${if (url.contains('?')) '&' else '?'}currentSchema=$schema" }
            registry.add("spring.datasource.username") { "postgres" }
            registry.add("spring.datasource.password") { password }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
        }
    }
}
