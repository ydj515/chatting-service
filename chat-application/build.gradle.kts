plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

dependencyManagement {
    imports {
        mavenBom(libs.spring.boot.dependencies.get().toString())
    }
}

dependencies {
    testImplementation(libs.archunit)
    testImplementation(project(":chat-protocol"))
    testImplementation(libs.spring.boot.starter.websocket)
    testImplementation(libs.spring.boot.starter.data.redis)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.jackson.datatype.jsr310)
    testImplementation(libs.micrometer.core)
    testImplementation(project(":chat-admin"))
    testImplementation(project(":chat-api-application"))
    testImplementation(project(":chat-admin-application"))
    testImplementation(project(":chat-websocket-application"))
    testImplementation(project(":chat-worker-application"))
    // 모든 하위 모듈 포함 (조립자 역할)
    implementation(project(":chat-runtime-config"))
    implementation(project(":chat-api"))
    implementation(project(":chat-domain"))
    implementation(project(":chat-core"))
    implementation(project(":chat-persistence"))
    implementation(project(":chat-websocket"))

    // 메인 애플리케이션 실행에 필요한 의존성만
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.actuator)

    // JPA 어노테이션 사용을 위해 필요 (@EnableJpaRepositories, @EnableJpaAuditing, @EntityScan)
    implementation(libs.spring.boot.starter.data.jpa)

    // 데이터베이스 (런타임에만 필요)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)
}

val architectureTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Checks production module boundaries and transaction ownership."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*ArchitectureTest") }
}

tasks.named<Test>("test") { exclude("**/*ArchitectureTest*") }
tasks.named("check") { dependsOn(architectureTest) }
