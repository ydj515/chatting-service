package com.chat.persistence.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class AuthConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
