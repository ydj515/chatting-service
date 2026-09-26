# ktlint Guidelines

이 문서는 Kotlin source와 Kotlin DSL의 format을 ktlint로 일관되게 검사하고 수정하기 위한
기준입니다. ktlint는 code style을 담당하며 복잡도와 잠재 bug 분석은 Detekt에 맡깁니다.

## 버전과 설정

- ktlint engine과 `org.jlleitschuh.gradle.ktlint` plugin 버전을 각각 exact version으로
  고정합니다. plugin 기본 engine 버전에 암묵적으로 의존하지 않습니다.
- formatting rule은 root `.editorconfig`에서 관리하고 IDE와 CI가 같은 설정을 읽게 합니다.
- formatter가 소유하는 whitespace, import ordering과 naming rule을 Detekt에 중복 선언하지
  않습니다.
- generated source는 실제 생성 경로만 filter에서 제외하고 application source를 넓게
  제외하지 않습니다.
- baseline은 기존 부채를 점진적으로 제거할 때만 사용하며 신규 프로젝트의 기본값으로
  생성하지 않습니다.

## Gradle 연결

- Kotlin DSL 시작점은 `templates/gradle/ktlint/build.gradle.kts.example`입니다.
- `ktlintCheck`는 읽기 전용 CI gate, `ktlintFormat`은 개발자가 명시적으로 실행하는 수정
  명령으로 분리합니다.
- `ignoreFailures`를 활성화하지 않고 `ktlintCheck`를 root `check` 또는 프로젝트 `verify`에
  연결합니다.
- multi-module build는 convention plugin에서 공통 설정을 관리하고 각 Kotlin module의
  `ktlintCheck`가 root 검증에 포함되는지 확인합니다.

## 검증

```sh
./gradlew ktlintCheck
./gradlew ktlintFormat
./gradlew check
```

format 명령 실행 후에는 변경 diff를 확인하고 check 명령을 다시 실행합니다. custom ruleset을
사용하면 dependency version과 rule id를 함께 고정하고 정상·위반 fixture로 검증합니다.

참고: [ktlint Gradle plugin](https://github.com/JLLeitschuh/ktlint-gradle),
[ktlint configuration](https://pinterest.github.io/ktlint/latest/rules/configuration-ktlint/)
