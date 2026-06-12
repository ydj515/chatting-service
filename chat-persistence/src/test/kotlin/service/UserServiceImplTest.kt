package com.chat.persistence.service

import com.chat.domain.dto.CreateUserRequest
import com.chat.persistence.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class UserServiceImplTest {

    @Test
    fun `이미 존재하는 사용자명은 상태 충돌 예외로 처리한다`() {
        val userRepository = mock(UserRepository::class.java)
        `when`(userRepository.existsByUsername("tester")).thenReturn(true)
        val userService = UserServiceImpl(userRepository)

        val exception = assertThrows(IllegalStateException::class.java) {
            userService.createUser(
                CreateUserRequest(
                    username = "tester",
                    password = "abc",
                    displayName = "테스터",
                )
            )
        }

        assertEquals("이미 존재하는 사용자명입니다: tester", exception.message)
    }
}
