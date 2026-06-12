# Build stage
FROM gradle:8.14-jdk17 AS build

WORKDIR /app
COPY . .

ARG APP_MODULE=chat-application

# Gradle wrapper 기준으로 프로젝트가 고정한 버전으로 빌드한다.
RUN ./gradlew ":${APP_MODULE}:bootJar" -x test --no-daemon

# Runtime stage
FROM amazoncorretto:17-alpine-jdk

WORKDIR /app

RUN apk add --no-cache curl

ARG APP_MODULE=chat-application

# 애플리케이션 JAR 복사
COPY --from=build /app/${APP_MODULE}/build/libs/*.jar app.jar

# 포트 노출
ARG APP_PORT=8080
EXPOSE ${APP_PORT}

ENV SPRING_PROFILES_ACTIVE=docker
ENV TZ=Asia/Seoul

# 애플리케이션 실행
ENTRYPOINT ["java", "-jar", "app.jar"]
