package com.chat.api.application

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaAuditing
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

@SpringBootApplication(
    scanBasePackages = [
        "com.chat.api",
        "com.chat.domain",
        "com.chat.persistence",
    ],
)
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = ["com.chat.persistence.repository"])
@EntityScan(basePackages = ["com.chat.domain.model"])
@ConfigurationPropertiesScan(basePackages = ["com.chat"])
class ChatApiApplication

fun main(args: Array<String>) {
    runApplication<ChatApiApplication>(*args)
}
