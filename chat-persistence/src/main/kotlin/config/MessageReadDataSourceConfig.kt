package com.chat.persistence.config

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import javax.sql.DataSource

@Configuration
@EnableConfigurationProperties(ChatReadDataSourceProperties::class)
class MessageReadDataSourceConfig {

    @Bean("jdbcTemplate")
    @Primary
    @ConditionalOnMissingBean(name = ["jdbcTemplate"])
    fun primaryJdbcTemplate(dataSource: DataSource): JdbcTemplate {
        return JdbcTemplate(dataSource)
    }

    @Bean
    @ConditionalOnProperty(
        prefix = "chat.datasource.read",
        name = ["enabled"],
        havingValue = "true",
    )
    fun messageReadDataSourceHolder(properties: ChatReadDataSourceProperties): MessageReadDataSourceHolder {
        return MessageReadDataSourceHolder(properties)
    }

    @Bean("messageReadJdbcTemplate")
    @ConditionalOnProperty(
        prefix = "chat.datasource.read",
        name = ["enabled"],
        havingValue = "true",
    )
    fun messageReadJdbcTemplate(holder: MessageReadDataSourceHolder): JdbcTemplate {
        return JdbcTemplate(holder.dataSource)
    }

    @Bean("messageReadJdbcTemplate")
    @ConditionalOnMissingBean(name = ["messageReadJdbcTemplate"])
    fun primaryFallbackMessageReadJdbcTemplate(@Qualifier("jdbcTemplate") jdbcTemplate: JdbcTemplate): JdbcTemplate {
        return jdbcTemplate
    }
}

class MessageReadDataSourceHolder(
    properties: ChatReadDataSourceProperties,
) : DisposableBean {
    init {
        require(properties.url.isNotBlank()) {
            "chat.datasource.read.url must be configured when chat.datasource.read.enabled=true"
        }
        require(properties.username.isNotBlank()) {
            "chat.datasource.read.username must be configured when chat.datasource.read.enabled=true"
        }
        require(properties.password.isNotBlank()) {
            "chat.datasource.read.password must be configured when chat.datasource.read.enabled=true"
        }
        require(properties.driverClassName.isNotBlank()) {
            "chat.datasource.read.driver-class-name must be configured when chat.datasource.read.enabled=true"
        }
    }

    val dataSource: HikariDataSource = HikariDataSource().apply {
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

    override fun destroy() {
        dataSource.close()
    }
}
