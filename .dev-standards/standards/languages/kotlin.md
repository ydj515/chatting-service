# Kotlin Guidelines & Standards

Kotlin 코드는 Java 보일러플레이트를 짧게 줄인 코드가 아니라 nullability, expression,
함수와 sealed type을 이용해 유효한 상태를 직접 표현해야 합니다. 간결함은 줄 수가 아니라
의도가 타입과 이름에서 드러나는 정도로 판단합니다.

## 1. Kotlin다운 기본 원칙

- Kotlin plugin/compiler와 stdlib 버전, `languageVersion`, `apiVersion`, JVM target 및 Java
  toolchain을 확인하고 해당 버전의 공식 권장 문법과 compiler 설정을 사용합니다.
  Gradle Kotlin DSL 버전과 애플리케이션 Kotlin 버전을 혼동하지 않습니다.
- compiler 옵션 DSL 전환은 사용 중인 Kotlin plugin의 migration guide를 기준으로 합니다.
  opt-in 기능과 compiler plugin, Detekt 등의 호환성을 확인하고 compiler 업그레이드 없이
  사용할 수 없는 문법을 예시에서 그대로 가져오지 않습니다.

- 변경이 필요하지 않은 local/property와 collection은 `val`과 read-only interface로
  선언합니다.
- `if`, `when`, `try`를 expression으로 사용하되 복잡한 chain보다 읽기 쉬운 named
  function과 guard clause를 우선합니다.
- 여러 동일 타입 또는 boolean parameter가 있으면 named argument를 사용합니다.
- 상태 없는 동작은 억지 `object XxxUtils`보다 top-level function이나 의미 있는 receiver의
  extension function으로 둡니다.
- `object`는 애플리케이션 전체에서 하나의 identity나 lifecycle이 필요한 경우에,
  `companion object`는 해당 class에 결합된 factory나 constant에 한정합니다. Java의
  `static`을 흉내 내기 위해 둘을 기본 선택으로 사용하지 않습니다.
- scope function은 의도가 분명할 때만 사용합니다. 중첩된 `let/run/apply/also`와
  암시적인 `it`, `this` 전환으로 제어 흐름을 숨기지 않습니다.

## 2. 타입으로 유효한 상태를 만든다

- 값 전달에는 `data class`, 식별자처럼 작은 wrapper에는 필요 시 `value class`, 닫힌 상태
  집합에는 `sealed interface/class`를 사용합니다.
- `when`은 sealed hierarchy와 enum을 exhaustively 처리합니다. 새 subtype 누락을 숨기는
  불필요한 `else`를 넣지 않습니다.
- `Pair`와 `Triple`은 짧은 local 변환에만 사용하고 API 반환에는 이름 있는 type을 둡니다.
- `copy()`는 편리하지만 domain 불변식을 우회할 수 있습니다. 모든 component 조합이
  유효하지 않은 model은 factory나 명시적 transition method를 사용합니다.

```kotlin
@JvmInline
value class OrderId(val value: Long)

sealed interface PaymentResult {
    data class Approved(val authorizationId: String) : PaymentResult
    data class Declined(val reason: String) : PaymentResult
}

fun PaymentResult.message(): String = when (this) {
    is PaymentResult.Approved -> "approved: $authorizationId"
    is PaymentResult.Declined -> "declined: $reason"
}
```

## 3. 함수 형태와 scope function 선택

- 동작이 객체의 핵심 불변식이나 private 상태를 사용하면 member function으로 둡니다.
  receiver의 공개 계약만으로 자연스럽게 읽히는 무상태 변환·표현은 extension function이
  적합합니다. 주 receiver가 없거나 여러 입력의 역할이 대등하면 top-level function을
  사용합니다.
- extension은 정적으로 결정되며 member를 override하지 않습니다. runtime polymorphism이나
  대체 구현이 필요한 동작을 extension으로 숨기지 않습니다.
- `let`은 nullable 값의 좁은 변환, `run`은 receiver 문맥에서 결과 계산, `apply`는 객체
  설정 후 같은 객체 반환, `also`는 logging처럼 값을 바꾸지 않는 부수 효과에 사용합니다.
  함수 이름만 줄이려고 scope function을 연쇄하지 않습니다.
- 여러 객체가 협력하거나 repository, clock, gateway 같은 의존성이 필요한 use case는
  extension이나 전역 `object`가 아니라 명시적으로 주입받는 class/function에 둡니다.

```kotlin
fun String.toOrderId(): OrderId = OrderId(trim().toLong())

fun calculateTotal(lines: List<OrderLine>, policy: PricingPolicy): Money =
    policy.calculate(lines)

val request = CreateOrderRequest()
    .apply { customerId = input.customerId }
    .also { logger.debug("created request for {}", it.customerId) }
```

`toOrderId`는 문자열을 receiver로 읽는 변환이고, 가격 계산은 두 입력과 policy 협력이
핵심이므로 `List<OrderLine>.calculateTotal(policy)` extension보다 일반 함수가 의존성을 더
명확하게 드러냅니다.

## 4. Collection 설계

- collection 변환은 `map`, `filter`, `associate`, `groupBy`, `mapNotNull`처럼 의도를
  직접 표현하는 표준 함수를 사용합니다.
- 여러 intermediate collection이 생기거나 조기 종료와 mutation이 더 명확하면 일반
  `for` loop를 사용합니다. `Sequence`는 큰 chain에서 이점이 측정될 때 선택합니다.
- default argument는 source-level 편의이며 Java 호출자와 binary compatibility 요구는
  별도로 검토합니다.

## 5. Null safety와 Java 경계

- `null`이 domain 상태라면 nullable type으로 명시하고, 여러 종류의 부재/실패라면
  sealed type으로 모델링합니다.
- `!!`은 금지 목록으로만 관리하지 말고 null 가능성이 유입된 경계를 찾아 safe call,
  Elvis, `requireNotNull` 또는 명시적 실패로 좁힙니다.
- Kotlin API에서 nullable과 같은 의미로 Java `Optional`을 중복 사용하지 않습니다.
- platform type은 adapter 경계에서 명시적인 nullable/non-null type으로 받아 내부로
  전파하지 않습니다.
- `@JvmStatic`, `@JvmOverloads`, `@JvmField`는 실제 Java 호출 계약이 필요할 때만 사용하고
  Kotlin 내부 편의를 위해 기본적으로 추가하지 않습니다.
- Java collection, array, stream과 Kotlin collection 사이의 변환 책임을 경계에 두고,
  반복적인 `toList`나 방어적 복사로 비용을 숨기지 않습니다.
- `lateinit`은 lifecycle이 보장되는 framework 주입 경계처럼 제한된 경우에만 사용하고
  일반 domain state에는 constructor initialization을 사용합니다.

## 6. 예외와 계약

- 호출자 입력의 전제는 `require`, 객체 상태 전제는 `check`로 표현합니다. 정상적인
  domain 거절을 무조건 `IllegalArgumentException`으로 축소하지 않습니다.
- 예상 가능한 여러 domain 결과는 sealed result type으로 표현할 수 있고, 복구 불가능한
  인프라 실패는 원인을 보존한 예외로 전파합니다.
- `runCatching`으로 넓은 블록을 감싸 cancellation이나 programmer error까지 값으로
  바꾸지 않습니다.

## 7. Coroutine과 Flow

- `suspend` 함수는 비동기라는 장식이 아니라 중단 가능한 계약입니다. 실제 suspend
  operation 없이 모든 함수를 `suspend`로 만들지 않습니다.
- `GlobalScope`와 수명 주기 없는 scope를 만들지 않습니다. coroutine은 호출자 scope의
  구조적 동시성 안에서 완료, 실패와 cancellation을 전달합니다.
- blocking I/O는 적절한 dispatcher로 격리하고 suspend API 안에서 thread를 막지 않습니다.
- `async`는 독립 작업을 동시에 실행하고 결과를 모두 기다릴 때 사용합니다. 순차 의존
  작업을 무조건 병렬화하지 않습니다.
- `Flow`, `SharedFlow`, `StateFlow`는 cold/hot, replay, backpressure와 state 의미를
  구분해 선택합니다.

## 8. KDoc과 공개 API

- library 공개 member와 extension point에는 안정적인 계약을 KDoc으로 작성합니다.
- `[Order]`, `[OrderRepository.findById]`처럼 symbol link를 사용하고 타입 정보를
  반복하지 않습니다.
- cancellation, thread-safety, side effect와 예외 조건처럼 signature만으로 드러나지
  않는 내용을 설명합니다.

```kotlin
/**
 * 현재 tenant에서 볼 수 있는 주문을 조회합니다.
 *
 * @throws OrderNotFoundException [orderId]가 없거나 접근할 수 없는 경우
 */
suspend fun loadOrder(orderId: OrderId): Order {
    // 구현 내용
}
```

## 9. 테스트와 품질 게이트

- sealed 분기, null 경계와 domain transition을 단위 테스트합니다.
- coroutine test는 실제 시간 지연보다 test dispatcher와 virtual time을 사용하고
  cancellation과 실패 전파를 확인합니다.
- ktlint는 format, Detekt는 code quality와 type-aware rule, ArchUnit은 JVM bytecode의 package
  의존성, Kover는 test coverage를 담당하게 합니다.
- Java와 함께 사용하는 build가 이미 JaCoCo를 표준 coverage gate로 운영하면 Kover를 추가로
  적용하지 않습니다.

```sh
./gradlew test check
./mvnw verify
```

Detekt rule 시작점은 `templates/detekt/`, Gradle 연결 예시는 `templates/gradle/detekt/`에
있습니다. ktlint, Kover와 ArchUnit 예시도 `templates/gradle/` 아래에서 제공합니다.

## 10. 다른 언어 습관을 옮기지 않는다

- mutable JavaBean, getter/setter, static utility class와 `Optional<T>`를 그대로 유지하지
  않습니다.
- 모든 null 처리에 `let`을 쓰거나 모든 collection 작업을 긴 chain으로 압축하지 않습니다.
- callback/future API를 wrapper만 씌워 coroutine처럼 보이게 하지 말고 cancellation과
  resource lifecycle까지 연결합니다.

참고: [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html),
[Kotlin Idioms](https://kotlinlang.org/docs/idioms.html),
[Null Safety](https://kotlinlang.org/docs/null-safety.html),
[Coroutines](https://kotlinlang.org/docs/coroutines-overview.html)

버전별 설정 참고: [Kotlin Gradle Compiler Options](https://kotlinlang.org/docs/gradle-compiler-options.html)
