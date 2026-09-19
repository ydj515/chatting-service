package com.chat.api.security

import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

class FixedCurrentAuthenticationResolver(
    private val userId: Long = 42L,
    private val sessionToken: String = "resolved-token",
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUserId::class.java) ||
            parameter.hasParameterAnnotation(CurrentSessionToken::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any =
        when {
            parameter.hasParameterAnnotation(CurrentUserId::class.java) -> userId
            parameter.hasParameterAnnotation(CurrentSessionToken::class.java) -> sessionToken
            else -> throw IllegalArgumentException("지원하지 않는 인증 파라미터입니다.")
        }
}
