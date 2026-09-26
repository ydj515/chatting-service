# Spring Framework Guidelines & Standards

Spring 코드는 annotation 수가 많은 코드가 아니라 container가 객체 조립, cross-cutting
concern과 lifecycle을 담당하고 domain/application 코드는 평범한 Java 또는 Kotlin으로
테스트 가능한 구조여야 합니다.

## 1. Spring다운 기본 원칙

- Spring Boot의 dependency management와 auto-configuration을 기본으로 사용하고 동일한
  infrastructure를 수동 설정과 중복 구성하지 않습니다.
- component scan 범위를 application package 아래로 제한하고 module 경계를 package와
  bean dependency 방향에 반영합니다.
- domain은 presentation, application, infrastructure와 config를 참조하지 않습니다. JPA
  annotation을 domain entity에 두는 방식은 persistence model과 domain model을 의도적으로
  통합할 때 허용하되 HTTP serialization 대상으로 직접 노출하지 않습니다.
- application startup 실패를 숨기기 위해 필수 bean이나 설정을 optional로 바꾸지
  않습니다. 필수 의존성은 빠르게 실패하게 합니다.

## 2. Spring architecture 적용

Spring을 선택하고 `architectures`를 생략하면 `layered-clean` profile을 함께 적용합니다.
Spring Boot는 특정 layout을 강제하지 않지만, 이 표준의 기본 구조는 최상위 계층 아래에
feature package를 두는 `계층 우선 + 기능 분할` 방식입니다. `@SpringBootApplication`
class는 전체 application의 root package에 둡니다.

### layered-clean 기본값

```text
com.example.shop/
├─ ShopApplication.kt
├─ presentation/order/
│  ├─ OrderController.kt
│  ├─ request/CreateOrderRequest.kt
│  ├─ response/OrderResponse.kt
│  └─ OrderExceptionHandler.kt
├─ application/order/
│  ├─ port/in/PlaceOrderUseCase.kt
│  ├─ port/out/SaveOrderPort.kt
│  ├─ port/out/PaymentGateway.kt
│  ├─ service/PlaceOrderService.kt
│  ├─ command/PlaceOrderCommand.kt
│  ├─ result/PlaceOrderResult.kt
│  └─ exception/OrderConflict.kt
├─ domain/order/
│  ├─ model/Order.kt
│  ├─ model/OrderId.kt
│  ├─ policy/OrderPolicy.kt
│  ├─ event/OrderPlaced.kt
│  └─ exception/OrderRejected.kt
├─ infrastructure/
│  ├─ persistence/order/
│  │  ├─ OrderJpaEntity.kt
│  │  ├─ OrderJpaRepository.kt
│  │  ├─ OrderPersistenceMapper.kt
│  │  └─ OrderPersistenceAdapter.kt
│  └─ client/payment/PaymentClientAdapter.kt
└─ config/ApplicationConfig.kt
```

- 허용 방향은 `presentation → application → domain`, `infrastructure → application/domain`,
  `config → application/infrastructure`입니다. domain은 바깥 계층을, application은
  presentation/infrastructure/config를 참조하지 않습니다.
- presentation은 request를 application `command`로, application `result`를 response로
  변환합니다. application의 `port/out`과 JPA entity를 직접 호출하거나 노출하지 않습니다.
- layered-clean 기본값에서는 저장소와 외부 client 계약을
  `application/<feature>/port/out`에 두고 infrastructure adapter가 구현합니다. domain은
  persistence 계약을 알지 않습니다.
- output port는 persistence 기술 type을 숨깁니다. Spring Data의 `Page`/`Pageable` 등을
  유지하는 편이 파생 쿼리와 paging 의미를 더 정확하게 보존하는 경우에는 의도적인 예외로
  문서화하고 application 경계에 최소한으로 노출합니다.
- `port/in`은 scheduler, listener 같은 기술 진입 adapter가 concrete use case에 의존하지
  않아야 할 때만 둡니다. 모든 use case 앞에 interface를 만들지 않습니다.
- `common`에는 request context accessor처럼 여러 계층이 사용하는 안정된 primitive만 두고
  domain 정책이나 범용 `Utils`를 모으지 않습니다.

```kotlin
package com.example.shop.application.order.service

@Service
class PlaceOrderService(
    private val orders: SaveOrderPort,
) : PlaceOrderUseCase {
    @Transactional
    override fun place(command: PlaceOrderCommand): PlaceOrderResult {
        val order = Order.place(command.customerId, command.lines)
        return orders.save(order).let { PlaceOrderResult(it.id, it.version) }
    }
}
```

`PlaceOrderService`는 application 흐름과 transaction을 소유합니다. controller는 command와
response mapping을, `OrderPersistenceAdapter`는 `SaveOrderPort`와 Spring Data/JPA 사이의
조회·저장 변환을 소유합니다.

### domain-oriented를 선택한 Spring DDD

업무 복잡도와 bounded context 경계가 분명한 Spring 프로젝트는 기본값을 명시적으로
`domain-oriented`로 바꿉니다.

```yaml
languages: [kotlin]
frameworks: [spring]
architectures: [domain-oriented]
```

```text
com.example.shop/
├─ ShopApplication.kt
├─ order/                         # bounded context
│  ├─ presentation/
│  │  ├─ OrderController.kt
│  │  ├─ request/CreateOrderRequest.kt
│  │  ├─ response/OrderResponse.kt
│  │  └─ OrderExceptionHandler.kt
│  ├─ application/
│  │  ├─ PlaceOrderUseCase.kt
│  │  ├─ command/PlaceOrderCommand.kt
│  │  ├─ result/PlaceOrderResult.kt
│  │  ├─ exception/OrderConflict.kt
│  │  └─ port/out/PaymentGateway.kt
│  ├─ domain/
│  │  ├─ model/Order.kt
│  │  ├─ model/OrderId.kt
│  │  ├─ repository/OrderRepository.kt
│  │  ├─ policy/OrderPolicy.kt
│  │  ├─ event/OrderPlaced.kt
│  │  └─ exception/OrderRejected.kt
│  └─ infrastructure/
│     ├─ persistence/OrderJpaEntity.kt
│     ├─ persistence/OrderJpaRepository.kt
│     ├─ persistence/OrderPersistenceMapper.kt
│     ├─ persistence/OrderRepositoryAdapter.kt
│     └─ client/PaymentClientAdapter.kt
├─ payment/
└─ config/ApplicationConfig.kt
```

- context 내부 의존 방향은 layered-clean과 동일하지만 최상위 분할을 계층이 아니라 bounded
  context로 둡니다. aggregate를 복원하는 repository 계약은 domain에 둘 수 있고, 외부
  gateway처럼 use case 전용인 계약은 application output port에 둡니다.
- request/response는 `<context>/presentation`, command/result는
  `<context>/application`, 업무 exception과 aggregate는 `<context>/domain`, JPA entity와
  client 오류는 `<context>/infrastructure`가 소유합니다.
- 다른 context의 aggregate, repository와 내부 service를 직접 import하지 않습니다. 공개
  application API, 식별자/value contract 또는 domain event로 협력합니다.
- context가 충분히 크고 독립적인 build/소유 경계가 필요하면 Gradle/Maven module로
  분리할 수 있지만 package 격리를 검증하기 전부터 multi-module을 기본값으로 만들지
  않습니다.
- JPA annotation을 aggregate에 유지할 수 있습니다. persistence 요구가 domain 불변식과
  생성 규칙을 왜곡하기 시작하면 infrastructure persistence model과 mapper를 분리합니다.

`domain-oriented`는 package 방향을 제공할 뿐 aggregate, domain service와 event를 모든
기능에 요구하지 않습니다. 전술 패턴은 실제 불변식, transaction과 context 간 협력 규칙이
있을 때만 적용합니다.

## 3. 의존성 주입과 bean 설계

- 필수 의존성은 constructor injection으로 받고 `final`/`val`로 유지합니다. field
  injection과 static `ApplicationContext` 조회를 사용하지 않습니다.
- setter injection은 실제 optional dependency나 runtime reconfiguration처럼 의미가
  분명할 때만 사용합니다.
- 하나의 구현만 있는 내부 service에 습관적으로 interface를 만들지 않습니다. module
  boundary, 대체 구현 또는 consumer contract가 있을 때 interface를 둡니다.
- constructor parameter가 계속 늘어나면 DI 문법을 줄이기보다 class 책임을 먼저
  분리합니다.
- `@Bean` factory는 외부 library 객체와 명시적인 infrastructure 조립에 사용하고,
  business object 생성을 거대한 configuration class에 모으지 않습니다.

```java
@Service
final class PlaceOrderService {
    private final OrderRepository orders;
    private final PaymentGateway payments;

    PlaceOrderService(OrderRepository orders, PaymentGateway payments) {
        this.orders = orders;
        this.payments = payments;
    }
}
```

## 4. 설정과 환경

- 서로 관련된 외부 설정은 validated `@ConfigurationProperties` type으로 묶습니다.
  여러 위치에서 `@Value` 문자열을 반복하지 않습니다.
- secret은 source와 기본 설정 파일에 기록하지 않고 환경별 secret provider에서
  주입합니다.
- profile은 bean graph 전체를 뒤바꾸는 만능 분기로 사용하지 않습니다. 환경 차이는
  가능한 한 설정 값과 명시적 adapter 선택으로 제한합니다.
- configuration key 변경은 배포 환경, 문서와 compatibility/default 동작을 함께
  검토합니다.

## 5. 계층과 Web 경계

- controller는 HTTP parsing, validation, authentication context와 response mapping을
  담당하고 use case를 호출합니다. transaction과 핵심 business rule을 controller에
  두지 않습니다.
- request/response DTO와 domain/persistence model을 분리합니다. JPA entity를 API로
  직접 직렬화하지 않습니다.
- Bean Validation은 외부 입력의 구조적 제약을 검사하고, 현재 상태에 따른 domain
  규칙은 domain/application layer가 검사합니다.
- 예외는 `@RestControllerAdvice` 등 단일 경계에서 안정적인 error contract로 변환하며
  내부 exception message와 stack trace를 외부에 노출하지 않습니다.

## 6. Transaction과 persistence

- transaction은 하나의 use case를 완성하는 application service의 public method에
  둡니다. repository 호출마다 잘게 나누거나 controller 전체를 넓게 감싸지 않습니다.
- 조회 전용 use case는 `readOnly = true`로 의도를 표현할 수 있지만, 이는 transaction
  manager와 persistence provider에 전달되는 hint이지 write를 항상 차단하는 안전장치가
  아닙니다.
- proxy 기반 `@Transactional`의 self-invocation, visibility와 rollback 규칙을 고려합니다.
  annotation이 보인다는 이유만으로 transaction이 적용됐다고 가정하지 않습니다.
- remote API 호출과 긴 CPU 작업을 database transaction 안에 포함하지 않습니다.
- 반복문 안의 repository 호출이나 lazy association 순회로 N+1이 생기지 않는지 query
  count로 검증하고, use case에 필요한 data를 fetch join, entity graph, batch 또는 projection
  중 적합한 방식으로 명시적으로 조회합니다. web serialization과 Open Session in View로
  조회 시점을 미루지 않습니다.
- retry가 필요한 transaction은 idempotency와 외부 side effect 순서를 함께 설계합니다.

## 7. 비동기와 scheduling

- `@Async`, scheduler와 event listener에는 executor, queue, timeout, retry, 중복 처리와
  shutdown 정책을 명시합니다.
- 같은 class 내부 호출로 proxy 기능을 우회하지 않습니다.
- 비동기 method의 오류가 관찰되지 않는 `void` 반환을 피하고 결과 또는 monitoring
  경계를 둡니다.
- reactive와 blocking stack을 한 request path에서 임의로 섞지 않습니다.

## 8. 테스트와 운영

- domain/application logic은 Spring context 없이 빠르게 단위 테스트합니다.
- MVC, persistence, JSON처럼 한 adapter를 검증할 때는 slice test를 사용하고 전체 bean
  graph가 필요한 시나리오만 `@SpringBootTest`로 검증합니다.
- production database, migration, transaction 의미가 중요한 repository는 실제 호환
  database로 integration test합니다.
- package 의존 방향과 transaction annotation 소유 계층은 architecture test로 고정합니다.
  예외가 필요하면 허용 목록에 기술적 이유와 제거 조건을 함께 기록합니다.
- JVM package 경계는 ArchUnit으로 검증하고, coverage는 Kotlin 중심 build의 Kover 또는
  범용 JaCoCo 중 하나를 `check`에 연결합니다.
- 모듈 의존 문서가 있으면 build tool이 읽은 실제 모듈·의존 방향과의 일치도 검사합니다.
  package 규칙, 문서 일치와 Spring wiring 검증은 각각 유지합니다. 구체적인 범위와
  lifecycle 연결 기준은 `tools/languages/java/archunit.md`를 따릅니다.
- Actuator endpoint는 노출 범위와 인증을 명시하고 readiness와 liveness 의미를 구분합니다.

```sh
./gradlew test check
./mvnw verify
```

## 9. Spring 안티패턴

- field injection, static context lookup, 거대한 `@Service`와 모든 class의 interface화
- controller에서 entity 직접 수정, transaction과 serialization 수행
- self-invocation 상태의 `@Transactional`, `@Async`, `@Cacheable`을 동작한다고 가정
- profile과 conditional bean 조합으로 실제 startup graph를 예측할 수 없게 만드는 구성

참고: [Spring Dependency Injection](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html),
[Declarative Transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html),
[Spring Data JPA Transactions](https://docs.spring.io/spring-data/jpa/reference/jpa/transactions.html),
[Spring Boot Testing](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html),
[Structuring Your Code](https://docs.spring.io/spring-boot/reference/using/structuring-your-code.html)
