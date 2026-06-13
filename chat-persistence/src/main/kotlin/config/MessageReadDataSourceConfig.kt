package com.chat.persistence.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

@Configuration
class MessageReadDataSourceConfig {

    @Bean("messageReadDataSource")
    @ConditionalOnProperty(
        prefix = "chat.datasource.read",
        name = ["enabled"],
        havingValue = "true",
    )
    fun messageReadDataSource(properties: ChatReadDataSourceProperties): DataSource {
        return HikariDataSource().apply {
            jdbcUrl = properties.url
            username = properties.username
            password = properties.password
            driverClassName = properties.driverClassName
            maximumPoolSize = properties.maximumPoolSize
            minimumIdle = properties.minimumIdle
            connectionTimeout = properties.connectionTimeout
            idleTimeout = properties.idleTimeout
            maxLifetime = properties.maxLifetime
            isReadOnly = true
            poolName = "message-read-pool"
        }
    }

    @Bean("messageReadJdbcTemplate")
    @ConditionalOnProperty(
        prefix = "chat.datasource.read",
        name = ["enabled"],
        havingValue = "true",
    )
    fun messageReadJdbcTemplate(
        @Qualifier("messageReadDataSource") dataSource: DataSource,
    ): JdbcTemplate {
        return JdbcTemplate(dataSource)
    }

    @Bean("messageReadJdbcTemplate")
    @ConditionalOnMissingBean(name = ["messageReadJdbcTemplate"])
    fun primaryFallbackMessageReadJdbcTemplate(jdbcTemplate: JdbcTemplate): JdbcTemplate {
        return jdbcTemplate
    }
}
