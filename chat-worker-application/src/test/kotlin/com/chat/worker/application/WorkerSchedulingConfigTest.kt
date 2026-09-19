package com.chat.worker.application

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WorkerSchedulingConfigTest {
    @Test
    fun `blocked export does not delay writer or cache retry scheduling`() {
        AnnotationConfigApplicationContext(WorkerSchedulingConfig::class.java).use { context ->
            val release = CountDownLatch(1)
            val started = CountDownLatch(1)
            val completed = CountDownLatch(2)
            val export = context.getBean("exportScheduler", ThreadPoolTaskScheduler::class.java)
            try {
                export.execute {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                listOf("writerScheduler", "sanctionCacheScheduler").forEach { name ->
                    context.getBean(name, ThreadPoolTaskScheduler::class.java).execute { completed.countDown() }
                }
                assertTrue(completed.await(5, TimeUnit.SECONDS))
                assertEquals(5, context.getBeansOfType(ThreadPoolTaskScheduler::class.java).size)
            } finally {
                release.countDown()
            }
        }
    }
}
