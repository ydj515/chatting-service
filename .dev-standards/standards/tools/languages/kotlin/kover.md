# Kover Guidelines

이 문서는 Kotlin/JVM 프로젝트에서 Kover로 test coverage를 보고하고 최소 기준을 검증하기 위한
기준입니다. coverage는 test가 실행한 경로를 보여 주지만 assertion과 scenario 품질을
증명하지 않습니다.

## 선택과 버전

- Kotlin 중심 Gradle build는 Kover를 기본 coverage 선택으로 사용할 수 있습니다.
- Java 중심 build 또는 기존 JaCoCo report pipeline을 유지해야 하면 JaCoCo를 사용합니다.
- 같은 module에 Kover와 JaCoCo plugin을 독립적으로 함께 적용하지 않습니다. Kover의
  `useJacoco()`는 Kover가 JaCoCo engine을 사용하는 단일 구성으로 취급합니다.
- `org.jetbrains.kotlinx.kover` plugin 버전을 version catalog와 lock 정책으로 고정합니다.
- Kotlin Multiplatform에서는 JVM test만 측정되며 JS와 native target은 별도 coverage가
  필요합니다.

## Report와 검증

- Kotlin DSL 시작점은 `templates/gradle/kover/build.gradle.kts.example`입니다.
- HTML은 로컬 분석, XML은 CI와 외부 report 연동에 사용하고 `koverVerify`를 `check` 또는
  프로젝트 `verify`에 연결합니다.
- line과 branch 기준을 모두 설정합니다. 기존 프로젝트는 현재 수치를 측정한 뒤 낮추지 않을
  기준에서 시작하고 신규 프로젝트는 template 값을 출발점으로 사용합니다.
- generated code와 외부 소유 code만 최소 범위로 제외하고 production package 전체를
  coverage 수치 때문에 숨기지 않습니다.
- threshold 실패는 test 추가만 요구하는 신호가 아닙니다. 누락된 동작, 불필요한 code 또는
  정당한 제외 중 무엇인지 먼저 판단합니다.

## Multi-module

- report를 생성할 root 또는 aggregation module을 하나 정합니다.
- 집계 대상 module을 `kover(project(":module"))` dependency로 명시하고 root의
  `koverHtmlReport`, `koverXmlReport`, `koverVerify`를 표준 진입점으로 사용합니다.
- 각 module에 서로 다른 coverage engine을 적용하지 않습니다.

## 검증

```sh
./gradlew koverHtmlReport
./gradlew koverXmlReport
./gradlew koverVerify
./gradlew check
```

Kover report와 verify task는 연결된 JVM test를 실행하므로 빠른 단위 feedback과 최종 coverage
gate를 별도 단계로 운영할 수 있습니다.

참고: [Kover Gradle plugin](https://kotlin.github.io/kotlinx-kover/gradle-plugin/),
[Kover plugin portal](https://plugins.gradle.org/plugin/org.jetbrains.kotlinx.kover)
