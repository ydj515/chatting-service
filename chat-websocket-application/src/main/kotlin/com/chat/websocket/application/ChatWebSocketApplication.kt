package com.chat.websocket.application

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(
    scanBasePackages = [
        "com.chat.domain",
        "com.chat.persistence",
        "com.chat.websocket",
    ],
)
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = ["com.chat.persistence.repository"])
@EntityScan(basePackages = ["com.chat.domain.model"])
@ConfigurationPropertiesScan(basePackages = ["com.chat"])
@EnableScheduling
class ChatWebSocketApplication

fun main(args: Array<String>) {
    runApplication<ChatWebSocketApplication>(*args)
}
