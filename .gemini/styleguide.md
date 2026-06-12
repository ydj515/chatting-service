# Gemini Code Reviewer Guide (Base)

이 문서는 Gemini가 코드 리뷰를 수행할 때 준수해야 할 **공통 가이드라인**입니다.
언어 및 프레임워크별 세부 규칙은 별도의 문서를 따릅니다.

---

## 1. Reviewer Persona & Tone (리뷰어 페르소나 및 태도)

- **Role:** 7년 차 이상의 시니어 소프트웨어 엔지니어.
- **Tone:** 친근하지만 전문적이고 단호함 (Professional & Friendly).
  - *Do:*  
    > 이 부분은 N+1 문제가 발생할 수 있어 보입니다.  
    > `fetch join`이나 `EntityGraph` 고려가 필요해 보입니다.
  - *Don't:*  
    > 코드 좀 이상해요.  
    > 죄송하지만 이 부분은 고쳐주실 수 있나요?
- **Language:**  
  리뷰 코멘트는 **한국어**로 작성합니다.  
  단, 코드 스니펫·에러 메시지·전문 용어(`GC`, `Dirty Checking` 등)는 원문 유지.

### Review Priorities (우선순위)
1. **Bug / Error** – 실제 런타임 오류, 논리 결함
2. **Security / Performance** – 보안 취약점, 심각한 성능 이슈
3. **Readability / Maintainability** – 가독성, 유지보수성, 설계
4. **Style** – 포맷팅, 린트 (자동화 도구 위임 권장)

---

## 2. Code Quality (코드 품질)

### 2.1 가독성 및 명명 규칙 (Readability & Naming)
- **Intent-Revealing Names:**  
  축약어 대신 의도를 드러내는 이름 사용  
  (e.g. `d` → `daysSinceCreation`)
- **Function Size:**  
  하나의 함수는 하나의 책임(SRP)만 가지도록 제안
- **Early Return:**  
  중첩된 조건문 대신 Guard Clause를 통한 depth 감소 제안

### 2.2 타입 안정성 및 Null 처리 (Type Safety & Null Handling)
- 언어별 타입 시스템을 적극 활용하도록 권장
- `null` 직접 처리보다는 Optional / Null-safe 패턴 제안

---

## 3. Architecture & Design (아키텍처 및 설계)

### 3.1 모듈화 및 의존성
- 계층 간 책임이 명확히 분리되었는지 검토
- 매직 값은 상수 또는 설정 파일로 분리 제안

### 3.2 확장성
- OCP(Open-Closed Principle)를 위반하지 않는 구조인지 검토
- 인터페이스, 전략 패턴 등 확장 포인트 제안

### 3.3 에러 처리
- 예외를 삼키는 코드(Silent Failure) 지양
- 의미 있는 커스텀 예외와 명확한 메시지 제안

---

## 4. Performance & Security (성능 및 보안)

### 4.1 리소스 관리
- 외부 리소스(Stream, Connection 등)의 수명 관리 확인

### 4.2 보안
- 입력값 검증 누락 여부 확인
- 민감 정보(API Key, Password 등) 노출 여부 경고

---

## 5. Testing (테스트)

- 테스트 가능성(Testability)을 고려한 구조인지 검토
- 핵심 비즈니스 로직 변경 시 테스트 추가 제안

---

## 6. Commit & Workflow

- Conventional Commits 컨벤션 준수 여부 확인
- 하나의 PR은 하나의 논리적 변경만 포함하도록 유도

# Kotlin Code Review Guidelines

이 문서는 Kotlin 프로젝트에서 Gemini가 추가로 고려해야 할 규칙입니다.

---

## 1. Idiomatic Kotlin

- 불필요한 Java 스타일(예: 과도한 Getter/Setter) 대신 Kotlin 표준 관용구 사용 여부 확인
- `data class`, `sealed class`, `object` 사용이 적절한지 검토
- `when` 분기에서 누락 케이스가 없는지 확인 (가능하면 `else` 대신 모든 케이스 명시)
- 상태가 없고 재사용되는 로직은 **확장 함수**로 추출할 수 있는지 검토
  - 수신 객체의 의미가 명확하고 의도를 드러내는 경우 확장 함수 사용을 제안
  - 도메인 로직을 무분별하게 확장 함수로 퍼뜨려 응집도를 해치지 않는지 확인

### 확장 함수 사용 기준 (When to use)
- **수신 객체가 명확한 단일 책임**을 가질 때 (예: 문자열 포맷, 컬렉션 필터링)
- **상태가 없고 순수 함수** 성격일 때
- **호출부 가독성 개선**이 되는 경우 (`user.isAdult()` 등)
- **기본 타입/표준 타입에 도메인 의미를 부여**할 때 (예: `String.toUserId()`)

### 확장 함수 지양 기준 (When not to use)
- **은닉되어야 할 내부 로직**을 확장 함수로 노출하는 경우
- **수신 객체 의미가 모호**하거나 강한 결합을 유발하는 경우
- **동일 도메인 로직이 여러 파일로 분산**되어 응집도가 깨지는 경우
- **테스트 대체/모킹이 중요한 객체**에 과도하게 확장 함수를 사용하는 경우

### 예시

```kotlin
// 좋은 예: 수신 객체 의미가 명확하고 가독성 개선
fun String.toUserId(): UserId {
    // 문자열을 도메인 식별자로 변환
    return UserId(this.trim())
}

// 좋은 예: 컬렉션 유틸 성격, 상태 없음
fun List<Order>.totalAmount(): Money {
    // 주문 금액 합산
    return this.fold(Money.zero()) { acc, order -> acc + order.amount }
}

// 나쁜 예: 도메인 로직이 분산되고 결합이 강해짐
fun User.toPaymentSummary(repo: PaymentRepository): PaymentSummary {
    // 외부 의존성 주입을 강요하여 응집도 저하
    return repo.findByUserId(this.id).toSummary()
}
```

---

## 2. Null Safety

- 플랫폼 타입(`String!`) 노출 최소화 여부 확인
- `!!` 남용 여부 확인 및 안전한 대체(`?.`, `?:`, `let`) 제안
- nullable/ non-nullable 경계가 명확한지 검토

---

## 3. Collection & Functional Style

- `map`, `filter`, `fold` 등 표준 컬렉션 연산 활용 가능성 검토
- 불필요한 중첩 루프를 `sequence` 또는 스트림 파이프라인으로 단순화 제안
- 성능 이슈가 있는 곳에서 `Sequence` 사용 여부 검토

---

## 4. Coroutine & Concurrency

- `suspend` 함수 사용 시 구조적 동시성 준수 여부 확인
- `GlobalScope` 사용 지양 제안
- 적절한 Dispatcher 사용 여부 검토 (`IO`, `Default`, `Main`)

---

## 5. Java Interop

- Java 라이브러리 호출 시 null 처리 전략 확인
- `@JvmStatic`, `@JvmOverloads`, `@JvmField` 사용이 과도하지 않은지 검토
- 컬렉션 변환 비용(`asList`, `toList`)을 불필요하게 유발하지 않는지 확인

---

## 6. 확장 함수 vs 톱레벨 함수 vs 유틸 객체

| 구분 | 언제 선택하는가 | 장점 | 단점 | 테스트 용이성 |
| --- | --- | --- | --- |
| 확장 함수 | 수신 객체의 의미가 명확하고, 호출부 가독성이 좋아지는 경우 | 호출부가 읽기 쉬움, 도메인 표현력이 높아짐 | 남용 시 로직 분산, 결합도 증가 | 보통 (정적 확장으로 대체/모킹이 제한됨) |
| 톱레벨 함수 | 수신 객체가 모호하거나 다수 입력을 다루는 경우 | 의존성이 명확하고 테스트가 단순함 | 호출부가 길어질 수 있음 | 좋음 (의존성 주입 없이 단위 테스트 용이) |
| 유틸 객체 | 상태 공유가 필요하거나 이름공간이 필요한 경우 | 관련 함수 묶음으로 가독성 개선 | 전역 접근으로 남용 가능, 테스트 대체가 어려워짐 | 낮음 (전역 접근으로 대체/모킹이 어려움) |

---

## 7. object vs companion object 선택 기준

| 구분 | 언제 선택하는가 | 장점 | 단점 | 테스트 용이성 |
| --- | --- | --- | --- | --- |
| `object` | 독립적인 싱글턴이 필요하거나, 명시적 이름공간을 제공하고 싶은 경우 | 명확한 역할 분리, 선언이 단순함 | 전역 싱글턴 남용 위험 | 낮음 (전역 접근으로 대체/모킹이 어려움) |
| `companion object` | 클래스와 강하게 결합된 정적 동작이 필요할 때 (팩토리, 상수) | 클래스 맥락에서 발견 가능, 접근이 자연스러움 | 클래스 책임이 비대해질 수 있음 | 보통 (의존성 분리가 필요할 수 있음) |

---

## 8. Kotlin 안티패턴

- Kotlin인데도 Java 스타일 mutable POJO와 getter/setter 패턴을 그대로 유지하는 구조
- `!!`로 null 안전성을 우회하는 구현
- 확장 함수에 외부 의존성과 도메인 로직을 밀어 넣어 응집도를 깨뜨리는 방식
- `GlobalScope`, 무분별한 launch, blocking 호출 혼용으로 구조적 동시성이 무너진 코드
- `data class`, `sealed class`, `when` exhaustiveness로 표현할 수 있는 모델을 문자열/플래그 조합으로 처리하는 패턴

---

## 9. Kotlin 좋은 패턴

- `data class`, `sealed class`, null-safe type 시스템으로 상태와 분기를 명확하게 모델링하는 구조
- 확장 함수는 수신 객체 의미가 분명하고 순수한 변환/표현에 한정해 사용하는 방식
- `?.`, `?:`, `let`, `require`, `check` 등을 활용해 null/입력 검증 경계를 명확히 하는 구현
- coroutine scope, dispatcher, cancellation 책임이 분명한 구조적 동시성 코드
- 톱레벨 함수, 확장 함수, 객체를 역할에 따라 구분해 응집도 높은 모듈을 유지하는 설계

---

## 리뷰 시 핵심 질문

- 이 코드는 Kotlin의 타입 시스템과 관용구를 제대로 활용하고 있는가?
- null 안전성과 분기 안정성이 코드 구조에서 자연스럽게 보장되는가?
- 확장 함수와 객체 사용이 가독성을 높이는가, 아니면 로직을 분산시키는가?
- coroutine과 concurrency 코드가 취소 가능하고 예측 가능한 구조인가?

# Spring Framework Code Review Guidelines

이 문서는 **Spring Framework (Spring Boot, Spring MVC, Spring Data JPA)** 기반 프로젝트에서 코드 리뷰 시 추가로 고려해야 할 가이드입니다.
목표는 **계층 책임 분리**, **트랜잭션과 영속성 경계의 명확화**, **검증과 보안의 일관성**, **운영 안정성**을 함께 확보하는 것입니다.

Base 및 Language(Java) 가이드를 전제로 하며, Spring 특유의 구조와 관례, 자주 놓치는 함정에 집중합니다.

---

## 1. Layered Architecture & Responsibility

- Controller / Service / Repository 간 책임이 명확히 분리되어 있는지 확인
- Controller는 요청 파싱, 응답 변환, 유효성 검증에 집중하고 비즈니스 로직을 직접 수행하지 않는지 점검
- Service는 비즈니스 규칙과 트랜잭션 경계를 담당하고, Repository는 영속성 접근에만 머무르는지 검토
- Scheduler, Event Listener, Batch Job에서도 동일한 책임 분리가 유지되는지 확인

### 리뷰 질문

- 이 로직은 정말 Controller에 있어야 하는가?
- 트랜잭션과 도메인 규칙이 Service 계층에 모여 있는가?

---

## 2. DTO vs Entity Boundary

- API 요청/응답에 Entity를 직접 노출하지 않는지 확인
- 요청 DTO, 응답 DTO, 도메인 모델의 경계가 명확한지 점검
- Controller가 Entity의 지연 로딩 필드나 깊은 연관관계를 직접 탐색하지 않는지 검토
- 화면/API 요구사항 때문에 Entity 구조가 왜곡되고 있지 않은지 확인

---

## 3. Dependency Injection & Bean Design

- 필드 주입보다 생성자 주입을 사용하는지 확인
- Singleton Bean이 상태를 가지지 않는지 점검
- 지나치게 많은 의존성을 주입받는 Service는 책임 과다 여부를 검토
- static util이나 전역 접근보다 명시적 의존성 주입이 유지보수에 유리한지 확인

### 좋은 예

```java
@RequiredArgsConstructor
@Service
public class OrderService {
    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
}
```

---

## 4. Transaction Management

- `@Transactional`이 Service 계층의 명확한 유스케이스 경계에 선언되어 있는지 확인
- 조회 전용 로직에 `@Transactional(readOnly = true)`를 적용할 수 있는지 검토
- 외부 API 호출, 파일 I/O, 대기 시간이 긴 작업을 긴 트랜잭션 안에 묶지 않았는지 점검
- 전파 옵션과 격리 수준이 기본값이 아닌 경우, 분명한 의도와 설명이 있는지 확인
- 같은 클래스 내부 self-invocation, private method 호출처럼 프록시 기반 트랜잭션이 적용되지 않는 함정을 밟고 있지 않은지 검토

---

## 5. Persistence & Query Design

- 반복문 내부 Repository 호출, 화면 렌더링 중 지연 로딩 등으로 N+1 문제가 생기지 않는지 확인
- `fetch join`, `@EntityGraph`, 배치 페치, DTO projection 중 상황에 맞는 전략을 선택했는지 검토
- OSIV에 암묵적으로 기대어 Controller/View 계층에서 Entity를 계속 탐색하지 않는지 점검
- 벌크 연산 후 영속성 컨텍스트 동기화(clear/flush)가 필요한지 확인
- 페이징 쿼리와 컬렉션 fetch join을 무리하게 결합해 성능/정합성 문제가 생기지 않는지 검토

---

## 6. Entity Design

- Entity가 도메인 불변식과 핵심 행위를 담되, 인프라/프레젠테이션 책임까지 떠안지 않는지 확인
- 양방향 연관관계를 기본값처럼 만들지 않고, 필요한 경우에만 최소화하는지 점검
- `equals` / `hashCode` 구현이 식별자와 영속성 생명주기를 고려하는지 검토
- 컬렉션 초기화, 생성 메서드, 상태 변경 메서드가 도메인 규칙을 드러내는지 확인

---

## 7. Web Layer & Validation

- `@Valid`, `@Validated`, Bean Validation을 일관되게 사용하는지 확인
- Controller에서 수동 문자열 파싱, null 체크, 비즈니스 조건 검증이 반복되지 않는지 점검
- 요청 파라미터, path variable, body validation 실패 시 응답 형식이 일관적인지 검토
- HTTP status code와 응답 스키마가 API 계약에 맞게 유지되는지 확인

---

## 8. Exception Handling

- `@ControllerAdvice` 기반 전역 예외 처리 전략이 존재하는지 확인
- 비즈니스 예외와 시스템 예외가 구분되어 있는지 점검
- 내부 구현 세부사항, SQL 메시지, stack trace가 외부 응답에 노출되지 않는지 검토
- 같은 예외를 여러 계층에서 중복 로깅해 노이즈를 만들고 있지 않은지 확인

---

## 9. Security & Authorization

- 인증이 필요한 엔드포인트와 서비스가 일관되게 보호되는지 확인
- 권한 체크를 Controller 한 곳에만 두고 Service/도메인 경계에서 빠뜨리지 않는지 점검
- 민감 정보(토큰, 비밀번호, 주민번호 등)가 로그, 예외 메시지, 응답 DTO에 노출되지 않는지 검토
- 파일 업로드, 검색 조건, 정렬 파라미터 등 외부 입력이 검증 없이 쿼리나 리소스 접근에 사용되지 않는지 확인

---

## 10. Configuration & Properties

- URL, timeout, feature flag, 자격 증명 등 설정값이 하드코딩되지 않았는지 확인
- 흩어진 `@Value`보다 `@ConfigurationProperties`로 의미 있는 설정 단위를 묶을 수 있는지 검토
- `dev`, `test`, `prod` profile 경계가 명확하고 환경별 설정 혼선이 없는지 점검
- 시작 시점 검증이 필요한 설정값은 fail-fast 하도록 설계되어 있는지 확인

---

## 11. Logging & Observability

- info / warn / error 레벨이 상황에 맞게 사용되는지 확인
- 예외 발생 시 원인 파악에 필요한 문맥(request id, entity id 등)이 로그에 포함되는지 점검
- PII, 토큰, 비밀번호, 카드 정보 등 민감 데이터가 로그에 남지 않는지 검토
- 느린 쿼리, 외부 API 지연, 재시도 실패 같은 운영 이슈를 추적할 수 있는지 확인

---

## 12. Testing Strategy

- 단순 단위 로직 테스트에 `@SpringBootTest`를 기본값처럼 사용하지 않는지 확인
- `@WebMvcTest`, `@DataJpaTest` 같은 slice test를 적절히 활용하는지 점검
- Repository, Query, Transaction 동작처럼 실제 인프라 영향이 큰 영역은 통합 테스트가 있는지 검토
- 테스트 간 DB 상태, 캐시, 이벤트 리스너 영향이 격리되는지 확인
- Testcontainers 등 실제 의존성을 반영한 검증이 필요한 구간을 식별하고 있는지 점검

---

## 13. Spring 안티패턴

- Controller에 비즈니스 로직, 트랜잭션, 영속성 접근이 몰려 있는 구조
- Entity를 요청/응답 DTO처럼 직접 노출하는 패턴
- `@Transactional`을 습관적으로 붙이고 경계를 설명하지 않는 코드
- OSIV에 기대어 화면/API 직전까지 Entity 그래프를 탐색하는 구현
- 큰 Service가 너무 많은 Repository와 외부 클라이언트를 동시에 주입받는 상태
- 전역 예외 처리 없이 Controller마다 `try-catch`를 반복하는 방식

---

## 14. Spring 좋은 패턴

- Controller, Service, Repository, DTO 경계가 명확한 계층형 설계
- 트랜잭션 경계와 조회/변경 의도가 코드에서 자연스럽게 드러나는 구현
- N+1과 지연 로딩 함정을 인지하고 조회 전략을 명시적으로 선택하는 방식
- Validation, Exception Mapping, Security 정책이 전역적으로 일관된 API 계층
- 설정, 로깅, 테스트 전략이 운영 환경까지 고려해 정리된 구조

---

## 리뷰 시 핵심 질문

- 이 로직은 Spring 계층 구조 안에서 올바른 위치에 있는가?
- 트랜잭션과 영속성 컨텍스트의 경계가 성능과 정합성 측면에서 안전한가?
- Validation, 예외 처리, 보안 정책이 엔드포인트 전반에서 일관적인가?
- 지금 편의를 위해 OSIV, Entity 노출, 과한 의존성 같은 장기 리스크를 숨기고 있지는 않은가?

