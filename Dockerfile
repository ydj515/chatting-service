# Build stage
FROM gradle:8.14-jdk17 AS build

WORKDIR /app
COPY . .

# Gradle wrapper 기준으로 프로젝트가 고정한 버전으로 빌드한다.
RUN ./gradlew clean build -x test --no-daemon

# Runtime stage
FROM amazoncorretto:17-alpine-jdk

WORKDIR /app

RUN apk add --no-cache curl

# 애플리케이션 JAR 복사
COPY --from=build /app/chat-application/build/libs/*.jar app.jar

# 포트 노출
ARG APP_PORT=8080
EXPOSE ${APP_PORT}

ENV SPRING_PROFILES_ACTIVE=docker
ENV TZ=Asia/Seoul

# 애플리케이션 실행
ENTRYPOINT ["java", "-jar", "app.jar"]
