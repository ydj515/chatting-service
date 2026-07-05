package com.chat.domain.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotBlank
import org.hibernate.Hibernate
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.jpa.domain.support.AuditingEntityListener
import java.time.LocalDateTime

@Entity
@Table(name = "app_users")
@EntityListeners(AuditingEntityListener::class)
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(unique = true, nullable = false, length = 50)
    @NotBlank
    val username: String,

    @Column(nullable = false, length = 255)
    val password: String,

    @Column(nullable = false, length = 100)
    val displayName: String,

    @Column(length = 500)
    val profileImageUrl: String? = null,

    @Column(length = 50)
    val status: String? = null,

    @Column(nullable = false)
    val isActive: Boolean = true,

    @Column
    val lastSeenAt: LocalDateTime? = null,

    @CreatedDate
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @LastModifiedDate
    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    // JPA 엔티티는 식별자(id) 기반 동등성을 사용한다.
    // 프록시/실제 인스턴스를 함께 비교하기 위해 Hibernate.getClass 로 실제 타입을 판별한다.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) return false
        other as User
        return id != 0L && id == other.id
    }

    // 영속화 전후로 값이 바뀌지 않도록 클래스 기준의 상수 해시를 사용한다.
    override fun hashCode(): Int = Hibernate.getClass(this).hashCode()

    override fun toString(): String = "User(id=$id, username=$username)"
}