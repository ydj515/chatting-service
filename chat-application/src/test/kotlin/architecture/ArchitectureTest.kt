package com.chat.application.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.transaction.annotation.Transactional

class ArchitectureTest {
    private val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.chat")

    @Test
    fun `transactions belong to application services`() {
        classes().that().areAnnotatedWith(Transactional::class.java).should().resideInAPackage("com.chat.core..service..").check(classes)
        classes.flatMap { it.methods }.filter { it.isAnnotatedWith(Transactional::class.java) }.forEach { method ->
            // The storage port keeps one partitioned batch atomic; the worker owns ACK/retry sequencing.
            val atomicStorageWrite = method.owner.name == "com.chat.persistence.service.PartitionedMessageWriteAdapter" && method.name == "write"
            assertTrue(method.owner.packageName.startsWith("com.chat.core.") || atomicStorageWrite, "Unexpected transaction owner: ${method.fullName}")
        }
    }

    @Test
    fun `production modules are present in the architecture test classpath`() {
        listOf("domain", "core", "protocol", "persistence", "api", "admin", "websocket", "worker", "application").forEach { module ->
            assertTrue(classes.any { it.packageName.startsWith("com.chat.$module") }, "Missing production module: $module")
        }
    }

    @Test
    fun `every executable composition root is inspected`() {
        listOf(
            "com.chat.application.ChatApplication",
            "com.chat.api.application.ChatApiApplication",
            "com.chat.admin.application.ChatAdminApplication",
            "com.chat.websocket.application.ChatWebSocketApplication",
            "com.chat.worker.application.ChatWorkerApplication",
        ).forEach { name ->
            assertTrue(classes.any { it.name == name }, "Missing composition root: $name")
            val application = Class.forName(name).getAnnotation(SpringBootApplication::class.java)
            assertTrue("com.chat.core" in application.scanBasePackages, "Core use cases are not scanned by $name")
        }
    }

    @Test
    fun `module packages have no dependency cycles`() {
        slices().matching("com.chat.(*)..").should().beFreeOfCycles().check(classes)
    }

    @Test
    fun `business and adapters do not depend on executable composition roots`() {
        noClasses().that().resideOutsideOfPackages("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.chat.application..", "com.chat.api.application..", "com.chat.admin.application..",
                "com.chat.websocket.application..", "com.chat.worker.application..",
            ).check(classes)
    }

    @Test
    fun `domain does not depend on infrastructure or delivery modules`() {
        noClasses().that().resideInAPackage("com.chat.domain..")
            .should().dependOnClassesThat().resideInAnyPackage("com.chat.core..", "com.chat.protocol..", "com.chat.persistence..", "com.chat.api..", "com.chat.admin..", "com.chat.websocket..")
            .check(classes)
    }

    @Test
    fun `core does not depend on concrete adapters or delivery`() {
        noClasses().that().resideInAPackage("com.chat.core..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.chat.persistence..", "com.chat.api..", "com.chat.admin..", "com.chat.websocket..",
                "org.springframework.data.jpa..", "org.springframework.jdbc..", "org.springframework.data.redis..",
                "org.springframework.web..", "software.amazon.awssdk..", "jakarta.validation..",
                "com.chat.protocol..", "com.fasterxml.jackson..", "io.micrometer..",
            ).check(classes)
    }

    @Test
    fun `pure policies are independent of Spring and adapters`() {
        noClasses().that().resideInAnyPackage("com.chat.core.room.policy..", "com.chat.core.message.policy..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "com.chat.persistence..")
            .check(classes)
    }

    @Test
    fun `shared wire contracts do not depend on application or adapters`() {
        noClasses().that().resideInAPackage("com.chat.protocol..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "com.chat.core..", "com.chat.persistence..", "com.chat.api..", "com.chat.admin..", "com.chat.websocket..",
                "org.springframework..", "jakarta.persistence..", "jakarta.validation..",
            ).check(classes)
    }

    @Test
    fun `websocket transport cannot depend on concrete storage adapters`() {
        noClasses().that().resideInAPackage("com.chat.websocket..")
            .and().resideOutsideOfPackage("com.chat.websocket.application..")
            .should().dependOnClassesThat().resideInAnyPackage("com.chat.persistence..", "org.springframework.data.redis..", "org.springframework.data.jpa..")
            .check(classes)
    }

    @Test
    fun `persistence does not depend on delivery modules`() {
        noClasses().that().resideInAPackage("com.chat.persistence..")
            .should().dependOnClassesThat().resideInAnyPackage("com.chat.api..", "com.chat.admin..", "com.chat.websocket..")
            .check(classes)
    }

    @Test
    fun `controllers use application services instead of repositories or JPA entities`() {
        noClasses().that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAnyPackage("com.chat.persistence.repository..", "com.chat.domain.model..", "com.chat.core..port..")
            .check(classes)
    }
}
