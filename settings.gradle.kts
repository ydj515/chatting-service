plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
rootProject.name = "chatting-service"

include(
    "chat-application",
    "chat-runtime-config",
    "chat-api-application",
    "chat-websocket-application",
    "chat-worker-application",
    "chat-admin-application",
    "chat-admin",
    "chat-domain",
    "chat-persistence",
    "chat-websocket",
    "chat-api"
)
