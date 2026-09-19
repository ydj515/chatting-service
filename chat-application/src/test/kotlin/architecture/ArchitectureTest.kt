package com.chat.application.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
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
        listOf("domain", "persistence", "api", "admin", "websocket").forEach { module ->
            assertTrue(classes.any { it.packageName.startsWith("com.chat.$module") }, "Missing production module: $module")
        }
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
