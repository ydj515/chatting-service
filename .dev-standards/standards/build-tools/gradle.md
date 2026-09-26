# Gradle Build Guidelines

이 문서는 Gradle 프로젝트의 빌드 재현성, 의존성 선언, 공통 빌드 로직과 검증
진입점을 일관되게 운영하기 위한 기준입니다. 실제 Gradle, plugin, dependency 버전은
각 프로젝트가 호환성을 확인한 뒤 고정합니다.

## Gradle Wrapper

- 시스템에 설치된 `gradle` 대신 저장소의 `./gradlew` 또는 `gradlew.bat`를 사용합니다.
- `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`,
  `gradle-wrapper.properties`를 함께 버전 관리합니다.
- Wrapper 버전 변경은 생성 task로 수행하고 script, JAR, properties 변경을 함께 검토합니다.
- `distributionUrl`은 의도한 배포본을 가리키게 하고 `distributionSha256Sum`으로
  내려받은 배포본을 검증합니다.
- Gradle build script와 Wrapper는 실행 코드이므로 신뢰하지 않는 변경을 검토 없이
  실행하지 않습니다.

## 버전별 DSL과 API 선택

- Wrapper의 Gradle 버전을 기준으로 같은 버전의 User Manual, DSL/API reference와 upgrade
  guide를 확인합니다. Gradle 실행 JDK, Java toolchain, Kotlin Gradle plugin과 품질 plugin의
  호환 범위를 함께 확인합니다.
- Kotlin DSL이 사용하는 Gradle 내장 Kotlin과 애플리케이션의 Kotlin compiler 버전을
  구분합니다. `build.gradle.kts`와 Groovy DSL 예시를 문법만 치환해 적용하지 않습니다.
- 해당 버전에서 지원되는 lazy task registration과 Provider 기반 설정을 우선하고,
  dependency resolution을 구성 단계에서 불필요하게 실행하지 않습니다. custom task는
  프로젝트의 configuration cache 정책에 맞춰 input/output과 실행 시 접근 대상을 설계합니다.
- 최신 문서의 incubating API는 Wrapper에서의 지원 여부와 안정성을 별도로 확인합니다.
  deprecation warning을 숨기지 말고 소유 코드와 외부 plugin의 경고를 구분해 전환 계획을 남깁니다.
- `./gradlew --version`으로 실제 실행 환경을 확인하고, DSL이나 task 구현을 변경하면 대상
  task를 실행합니다. configuration cache를 사용하는 프로젝트는 재실행 시 cache 재사용과
  입력 변경 후 검증 재수행까지 확인합니다.

## Version Catalog

- 기본 catalog는 root build의 `gradle/libs.versions.toml`에 둡니다.
- `[versions]`에는 여러 library 또는 plugin에서 공유할 버전만 이름을 부여합니다.
- `[libraries]`에는 dependency 좌표를 alias로 선언하고 공유 버전은 `version.ref`로
  참조합니다.
- `[plugins]`에는 외부 plugin id와 버전을 선언하고 build script에서는
  `alias(libs.plugins.<alias>)`로 적용합니다.
- `[bundles]`는 항상 함께 사용하는 dependency 집합에만 사용합니다. compile과 runtime
  범위가 다른 dependency를 편의를 위해 하나로 묶지 않습니다.
- alias는 build script의 공개 이름으로 취급하고 의미 없이 변경하지 않습니다.
- Version Catalog는 요청 버전을 중앙화하지만 dependency resolution 결과를 강제하지
  않습니다. 강제가 필요하면 platform, constraint 또는 dependency locking 정책을
  별도로 적용합니다.

최소 구조는 `templates/gradle/libs.versions.toml.example`을 참고하되, 모든 버전
자리표시자는 프로젝트가 검증한 값으로 교체합니다.

Checkstyle, PMD, SpotBugs, Detekt, ktlint, ArchUnit, Kover와 JaCoCo의 Kotlin DSL 시작점은
`templates/gradle/<tool>/build.gradle.kts.example`에 둡니다. 여러 도구를 함께 사용할 때는
각 예제의 plugin 선언을 하나의 `plugins` block으로 합치고 설정과 task block은 도구별로
유지합니다. Kover와 JaCoCo는 coverage 책임이 겹치므로 하나만 선택합니다. 조직이 repository
mirror나 allowlist를 사용하면 예제의 `mavenCentral()`을 해당 프로젝트의 repository 정책으로
교체합니다.

## Dependency resolution과 검증

- `1.+`, version range, `latest.*` 같은 동적 버전은 기본적으로 사용하지 않습니다.
  불가피하게 사용하면 모든 관련 configuration에 dependency locking을 활성화하고 생성된
  lock state를 버전 관리합니다.
- `-SNAPSHOT` 같은 changing module은 같은 좌표의 내용이 바뀌므로 lockfile만으로 재현성을
  보장할 수 없습니다. release와 필수 CI 경로에서는 사용하지 않습니다.
- 외부 dependency와 plugin은 `gradle/verification-metadata.xml`의 SHA-256 이상 checksum을
  기본으로 검증하고 가능한 artifact에는 signature 검증을 함께 적용합니다. 새 artifact를
  추가할 때 자동 생성한 값을 그대로 승인하지 않고 repository와 배포 출처를 함께 검토합니다.
- dependency lock과 verification metadata는 실제 resolution 결과에 종속되므로 중앙
  템플릿에서 복사하지 않습니다. 소비 저장소가 생성하고 변경 diff를 소유합니다.

```sh
./gradlew dependencies --write-locks
./gradlew --write-verification-metadata sha256
```

## 빌드 로직

- root build는 공통 orchestration에 집중하고 각 subproject는 자신의 plugin과
  dependency를 선언합니다.
- 여러 subproject에 반복되는 compiler, test, publishing 설정은 convention plugin으로
  추출합니다.
- 작은 build는 `buildSrc`, 규모가 크거나 독립 검증이 필요하면 `build-logic` included
  build를 사용합니다.
- 광범위한 `allprojects`와 `subprojects` 설정으로 숨은 결합을 만들지 않습니다.
- 직접 만든 verification task는 표준 `check` lifecycle에 연결합니다.
- JVM 모듈 의존 diagram은 Gradle이 평가한 그래프와 비교하고 `architectureTest` 및 root
  `check`에서 실행되게 합니다. root `check`가 subproject의 `check`를 자동 집계한다고
  가정하지 말고 실제 task 연결을 확인합니다. 비교 범위와 실패 fixture 기준은
  `tools/languages/java/archunit.md`의 문서·그래프 일치 검증 절을 따릅니다.

## 검증

사용 가능한 task와 연결 관계를 먼저 확인합니다.

```sh
./gradlew tasks --group verification
./gradlew test
./gradlew check
```

의존성 충돌이나 예상하지 않은 선택 버전을 조사할 때는 대상 configuration을 지정해
다음 명령을 사용합니다.

```sh
./gradlew dependencies --configuration runtimeClasspath
./gradlew dependencyInsight --dependency <module> --configuration runtimeClasspath
```

- CI와 로컬은 같은 Wrapper 및 검증 진입점을 사용합니다.
- 품질 plugin은 보고서만 만들고 성공 처리하지 않도록 실패 조건을 명시합니다.
- Wrapper, Kotlin, JDK 또는 주요 plugin 버전을 변경하면 clean build와 전체 `check`를
  실행하고 deprecation warning을 검토합니다.

참고: [Gradle Dependency Locking](https://docs.gradle.org/current/userguide/dependency_locking.html),
[Gradle Dependency Verification](https://docs.gradle.org/current/userguide/dependency_verification.html)

버전별 설정 참고: [Gradle Compatibility Matrix](https://docs.gradle.org/current/userguide/compatibility.html)
