package com.chat.application.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional

class ArchitectureTest {
    private val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.chat")

    @Test
    fun `transactions belong to persistence application services`() {
        classes().that().areAnnotatedWith(Transactional::class.java).should().resideInAPackage("com.chat.persistence.service..").check(classes)
        methods().that().areAnnotatedWith(Transactional::class.java).should().beDeclaredInClassesThat().resideInAPackage("com.chat.persistence.service..").check(classes)
    }

    @Test
    fun `production modules are present in the architecture test classpath`() {
        listOf("domain", "persistence", "api", "admin", "websocket", "worker", "application").forEach { module ->
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
            .should().dependOnClassesThat().resideInAnyPackage("com.chat.persistence..", "com.chat.api..", "com.chat.admin..", "com.chat.websocket..")
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
            .should().dependOnClassesThat().resideInAnyPackage("com.chat.persistence.repository..", "com.chat.domain.model..")
            .check(classes)
    }
}
