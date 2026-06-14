package com.chat.admin.config

import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AdminPropertiesTest {

    @Test
    fun `admin token은 blank 값을 허용하지 않는다`() {
        val validator = Validation.buildDefaultValidatorFactory().validator

        val violations = validator.validate(
            AdminProperties(
                token = "   ",
            ),
        )

        assertTrue(violations.any { it.propertyPath.toString() == "token" })
    }
}
