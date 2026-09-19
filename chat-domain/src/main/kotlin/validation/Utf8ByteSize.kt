package com.chat.domain.validation

import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [Utf8ByteSizeValidator::class])
annotation class Utf8ByteSize(
    val max: Int,
    val message: String = "UTF-8 byte length is too long",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = [],
)

class Utf8ByteSizeValidator : ConstraintValidator<Utf8ByteSize, CharSequence> {
    private var maxBytes: Int = 0

    override fun initialize(constraintAnnotation: Utf8ByteSize) {
        maxBytes = constraintAnnotation.max
    }

    override fun isValid(value: CharSequence?, context: ConstraintValidatorContext): Boolean = value == null || value.toString().toByteArray(Charsets.UTF_8).size <= maxBytes
}
