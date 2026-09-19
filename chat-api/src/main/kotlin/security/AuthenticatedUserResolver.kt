package com.chat.api.security

import com.chat.domain.exception.UnauthenticatedException
import com.chat.domain.service.SessionTokenService
import org.springframework.core.MethodParameter
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

@Component
class AuthenticatedUserResolver(
    private val sessionTokenService: SessionTokenService,
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        supportsCurrentUserId(parameter) || supportsCurrentSessionToken(parameter)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val token = resolveBearerToken(webRequest.getHeader(HttpHeaders.AUTHORIZATION))
        return when {
            parameter.hasParameterAnnotation(CurrentUserId::class.java) -> authenticateUserId(token)
            parameter.hasParameterAnnotation(CurrentSessionToken::class.java) -> token
            else -> throw IllegalArgumentException("지원하지 않는 인증 파라미터입니다.")
        }
    }

    private fun authenticateUserId(token: String): Long =
        sessionTokenService.authenticate(token)?.userId
            ?: throw UnauthenticatedException("유효하지 않은 인증 토큰입니다.")

    private fun resolveBearerToken(authorizationHeader: String?): String =
        authorizationHeader
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw UnauthenticatedException("인증 토큰이 필요합니다.")

    private fun supportsCurrentUserId(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentUserId::class.java) &&
            (parameter.parameterType == java.lang.Long.TYPE || parameter.parameterType == java.lang.Long::class.java)

    private fun supportsCurrentSessionToken(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(CurrentSessionToken::class.java) &&
            parameter.parameterType == String::class.java

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
