# ArchUnit Guidelines

이 문서는 Java와 Kotlin/JVM 프로젝트의 package, layer와 dependency 방향을 ArchUnit test로
검증하기 위한 기준입니다. ArchUnit은 compile된 JVM bytecode를 분석하므로 framework 문서의
의존 규칙을 실행 가능한 test로 고정하는 데 사용합니다.

## 의존성과 실행

- JUnit 5 프로젝트는 `com.tngtech.archunit:archunit-junit5` version을 test dependency로
  고정합니다.
- Gradle Kotlin DSL 시작점은 `templates/gradle/archunit/build.gradle.kts.example`입니다.
- architecture test 이름과 Gradle filter를 일치시키고 root `check` 또는 프로젝트 `verify`가
  반드시 실행하게 합니다.
- production class만 import하고 test fixture, generated class와 third-party package는 분석
  범위에서 제외합니다.
- multi-module 검사는 각 대상 모듈의 production output을 분석 classpath에 포함하고 필요한
  compile task와 연결합니다. 대표 class/package의 존재를 확인해 일부 모듈만 import된 상태를
  성공으로 처리하지 않습니다.
- test 이름 filter, JUnit engine과 source set 설정으로 test가 실제 발견되는지 확인합니다.
  `NO-SOURCE`, `SKIPPED` 또는 실행된 test 0개는 최초 연결 검증의 성공 증거가 아닙니다.
  `test`와 별도 task의 중복 실행 여부도 명시합니다.
- ArchUnit, JUnit과 Gradle API는 프로젝트의 고정 버전에서 지원되는 구성을 사용합니다.
  분석 대상 JDK bytecode와 ArchUnit의 호환성도 확인합니다.

## Rule 설계

- package 이름이 아니라 실제 architecture profile의 dependency 방향을 먼저 정의합니다.
- layer access, domain의 framework 독립성, transaction annotation 소유 위치와 slice cycle처럼
  변경 시 영향이 큰 규칙부터 추가합니다.
- class suffix, 모든 public method와 같은 구현 세부를 과도하게 고정하지 않습니다.
- 예외는 전체 rule을 비활성화하지 않고 최소 package/class만 허용하며 기술적 이유와 제거
  조건을 기록합니다.
- `allowEmptyShould(true)`로 package 오타나 빈 분석 범위를 숨기지 않습니다.
- reflection, DI 설정과 런타임 호출 관계는 bytecode 의존 검사만으로 보장하지 않습니다.
  필요한 wiring/기동 검증은 integration test로 분리합니다.

## Java와 Kotlin

JUnit integration의 `@AnalyzeClasses`와 `@ArchTest`를 사용하면 imported class cache와 test
실행을 재사용할 수 있습니다. Kotlin source도 JVM bytecode로 분석되지만 top-level declaration,
companion object와 synthetic class 이름에 직접 결합하는 규칙은 피합니다.

architecture profile이 바뀌면 정상 dependency와 각 금지 방향을 나타내는 작은 fixture를
같은 변경에서 갱신합니다.

## 문서와 Gradle 모듈 의존 그래프의 일치 검증

- 모듈과 의존 방향을 설명하는 architecture 문서에는 실제 Gradle 의존 그래프와의 일치를
  검증하는 test도 추가합니다. ArchUnit의 bytecode 검증과 별도로, 코드에서 아직 사용하지
  않는 Gradle project dependency와 문서의 누락·역방향 화살표까지 검사합니다.
- 비교 대상 문서와 diagram을 명시하고, diagram의 module ID를 Gradle project path에
  매핑합니다. 문서의 `A → B`는 "A가 B에 의존한다"는 의미로 고정합니다. 호출 흐름이나
  데이터 흐름 diagram은 이 검사에 포함하지 않습니다.
- 동일 그래프가 여러 문서에 반복되면 기준 데이터에서 생성하고 생성물의 최신 여부를
  검사하거나, 각 문서를 같은 실제 그래프와 대조합니다. 검사 중 문서를 자동 수정해 차이를
  없애지 않고 갱신 명령과 검증 명령을 분리합니다.
- diagram ID, node alias, label과 group의 의미 및 지원 문법을 고정합니다. 표시 이름 변경은
  module ID 변경과 구분하고 중복 ID나 모호한 매핑은 실패시킵니다. 여러 project를 하나의
  node로 묶으면 명시적 매핑을 사용하며 그룹 내부 의존 검사도 별도로 유지합니다.
- 문서가 전체 모듈을 설명하는지 특정 하위 모듈만 설명하는지 명시합니다. 전체 범위에서는
  Gradle에 포함된 대상 project 집합과 문서의 module 집합이 같아야 합니다. 부분 범위에서는
  포함할 project와 경계 밖 의존의 처리 기준을 명시하며, 문서에 나온 모듈만으로 검사 범위를
  정해 누락을 숨기지 않습니다.
- Gradle build script를 정규식으로 읽거나 별도 목록에 실제 의존 관계를 복사하지 않습니다.
  Gradle이 평가한 project/configuration 모델과 선택한 configuration의 resolution 결과를
  사용합니다. convention plugin, configuration 상속과 dependency substitution이 반영된
  결과를 기준으로 삼습니다.
- production `compileClasspath`와 `runtimeClasspath` 등 비교할 configuration을 명시합니다.
  test, fixture, build tooling, external library와 included build의 포함 여부도 고정합니다.
  configuration별로 비교하거나 합집합으로 비교할지 문서에 기록합니다.
- 기본 비교 단위는 직접 project dependency입니다. resolution 결과의 전이 의존을 직접
  화살표로 취급하지 않습니다. 문서의 화살표가 실제 의존이 아닌 "허용 가능한 의존"을
  나타낸다면 별도 diagram으로 분리하고, 실제 의존 집합이 허용 집합의 부분집합인지 검사합니다.
- 각 source project의 선택한 configuration에서 root의 직접 dependency edge를 추출합니다.
  dependency constraint를 실제 의존 edge로 세지 않고, substitution은 요청 대상과 선택된
  대상을 구분해 선택된 project를 비교합니다. included build를 포함하면 build 식별자와
  project path를 함께 사용해 같은 path의 충돌을 막습니다.
- plugin에 따라 configuration이 없거나 variant가 여러 개일 수 있습니다. 지원하는 plugin과
  variant를 명시하고 필수 configuration 누락을 빈 그래프로 대체하지 않습니다. property나
  profile에 따라 그래프가 달라지면 CI에서 검증할 조합을 고정합니다.
- 실제 의존을 설명하는 diagram은 module 집합과 방향이 있는 edge 집합을 각각 정확히
  비교합니다. 문서에만 있는 모듈·화살표, Gradle에만 있는 모듈·화살표와 반대 방향 의존을
  실패로 처리하고, 실패 메시지에 project path, configuration과 차이를 출력합니다.
- 지원하지 않는 diagram 문법, 알 수 없는 module ID, 빈 비교 범위와 dependency resolution
  실패는 검증 실패로 처리합니다. 파싱할 수 없는 항목을 건너뛰어 성공시키지 않습니다.

기존 `architectureTest`에는 그래프 비교 task를 선행 작업으로 연결할 수 있습니다.
프로젝트에서 `verifyArchitectureDiagram` task를 구현·등록한 뒤 아래 연결을 추가합니다.
이 예시는 연결 방식만 나타내며, 그래프 추출과 문서 비교 구현을 제공하지 않습니다.

```kotlin
tasks.named("architectureTest") {
    dependsOn("verifyArchitectureDiagram")
}
```

multi-project build에서는 전체 비교를 담당하는 root task와 각 모듈의 `architectureTest`
경로를 명시적으로 연결합니다. 기존 `check → architectureTest` 연결을 유지하고, 그래프 비교
task가 다시 `architectureTest`에 의존하는 순환을 만들지 않습니다. 문서와 관련 Gradle 설정의
변경이 검사에 반영되도록 task input을 선언하고, 모델 변경을 추적할 수 없다면 검증 결과를
재사용하지 않습니다.

검사 자체에는 일치하는 정상 fixture와 함께 모듈 누락·추가, 화살표 누락·추가·역전,
configuration별 의존, 직접·전이 의존 구분과 잘못된 diagram의 실패 fixture를 둡니다.
Gradle TestKit 등 프로젝트의 기존 검증 방식으로 문서만 바뀌거나 Gradle 의존만 바뀌어도
`architectureTest`와 `check`가 실패하는지 확인합니다.

문서와 그래프가 같더라도 금지 의존이나 순환이 함께 추가될 수 있으므로 허용 방향과 cycle
규칙은 독립적으로 유지합니다. module/edge 출력은 정렬해 재현 가능한 차이를 제공하고,
예외에는 대상, 이유와 제거 조건을 기록합니다. cache를 사용하는 프로젝트는 최초 실행 후
문서만 변경한 경우와 Gradle 설정만 변경한 경우에도 검증이 다시 수행되는지 확인합니다.

Maven 프로젝트에는 위 Gradle task 예시를 그대로 적용하지 않습니다. 같은 문서 비교 원칙을
사용하되 활성 profile의 reactor module, effective POM과 resolved dependency를 기준으로
scope와 직접 의존을 정의하고 `verify` lifecycle에 연결합니다. 현재 Gradle template과 Maven
template은 문서·그래프 비교 구현을 포함하지 않습니다.

## 검증

```sh
./gradlew architectureTest
./gradlew check
```

```sh
./mvnw test
./mvnw verify
```

참고: [ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html),
[Gradle Graph Resolution](https://docs.gradle.org/current/userguide/dependency_graph_resolution.html),
[Gradle Configuration Cache Requirements](https://docs.gradle.org/current/userguide/configuration_cache_requirements.html)
