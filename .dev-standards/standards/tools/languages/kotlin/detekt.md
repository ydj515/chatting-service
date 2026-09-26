# Detekt Guidelines

이 문서는 Kotlin 프로젝트에서 Detekt를 재현 가능한 품질 게이트로 운영하기 위한
기준입니다. Detekt plugin 버전, plugin id, 규칙 설정은 사용하는 major 버전에 맞춰
각 프로젝트가 고정합니다.

## 빌드 연결

- 공식 Gradle plugin이 제공하는 `detekt` task를 기본 분석 진입점으로 사용합니다.
- JVM 프로젝트에서 type resolution이 필요한 규칙은 `detektMain`과 `detektTest`를 사용합니다.
- Kotlin Multiplatform과 Android는 생성되는 target 또는 variant별 task를 먼저 확인합니다.
- `check` 또는 프로젝트의 `verify`가 필요한 Detekt task를 빠짐없이 실행하게 합니다.
- `ignoreFailures`로 결과를 무시하지 않고 CI에서 새 위반이 실패로 나타나게 합니다.
- Maven 프로젝트는 Detekt CLI를 실행하는 plugin execution을 `verify` phase에 연결하고
  실행 실패가 build 실패로 전파되게 합니다.
- CLI로 type resolution을 사용할 때는 분석 대상과 일치하는 classpath와 JVM target을
  명시합니다.

## 설정 관리

- Detekt 설정은 저장소에 커밋하고 build 설정에서 경로를 명시합니다.
- Kotlin DSL 시작점은 `templates/gradle/detekt/build.gradle.kts.example`을 사용합니다.
- `buildUponDefaultConfig`를 사용하면 파일에서 생략한 기본 규칙도 활성 상태일 수 있음을 문서화합니다.
- major 버전을 변경할 때는 먼저 기본 설정을 다시 생성하고 제거·변경된 rule id를 점검합니다.
- formatter와 중복되는 규칙은 어느 도구가 소유하는지 정하고 한쪽에서만 강제합니다.

## baseline과 예외

- 기존 위반을 한 번에 수정할 수 없을 때만 baseline을 사용합니다.
- baseline은 기존 부채를 격리하는 수단이며 새 위반을 허용하는 설정으로 사용하지 않습니다.
- `@Suppress`에는 좁은 rule id와 코드만으로 알 수 없는 이유를 남깁니다.
- 광범위한 경로 제외보다 생성물 또는 외부 코드처럼 소유권이 다른 경로만 제외합니다.

## 커스텀 규칙

- 커스텀 rule set artifact를 `detektPlugins` configuration에 연결합니다.
- `RuleSetProvider` 구현과 `META-INF/services` 등록을 함께 검증합니다.
- root Detekt task보다 커스텀 rule module 조립이 먼저 실행되도록 task 의존성을 연결합니다.
- 커스텀 규칙에는 정상·위반·예외 사례를 검증하는 단위 테스트를 둡니다.

## 검증

Gradle 프로젝트는 존재하는 task를 먼저 확인합니다.

```sh
./gradlew detekt
./gradlew check
```

type resolution을 품질 게이트로 사용하는 JVM 프로젝트는 다음 task도 실행합니다.

```sh
./gradlew detektMain detektTest
```

Maven 프로젝트는 Detekt execution을 포함한 전체 lifecycle을 실행합니다.

```sh
./mvnw verify
```
