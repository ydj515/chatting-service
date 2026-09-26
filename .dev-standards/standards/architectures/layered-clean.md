# Layered Clean Architecture Guidelines & Standards

Layered-clean 구조는 delivery와 infrastructure가 application/domain을 향하도록 의존
방향을 고정합니다. 최상위 package를 계층으로 나누고 각 계층 아래에 feature를 둡니다.
이 저장소에서는 Spring 애플리케이션의 기본 profile로 사용합니다.

## 1. 적용 기준

- HTTP, batch, message listener 등 여러 delivery adapter가 같은 use case를 호출합니다.
- application 흐름과 domain 규칙을 framework와 persistence 구현에서 분리해야 합니다.
- feature가 아직 bounded context로 독립되지 않았고 전체 애플리케이션이 하나의 일관된
  계층 구조를 유지할 수 있습니다.
- repository와 외부 client 계약은 application output port에 두고 domain은 업무 모델과
  순수 규칙만 소유하는 것을 기본값으로 합니다.

## 2. 기본 구조와 의존 방향

`<ext>`는 선택 언어의 확장자로 바꿉니다.

```text
src/main/<source-root>/
├─ presentation/
│  └─ order/
│     ├─ OrderController.<ext>
│     ├─ request/CreateOrderRequest.<ext>
│     ├─ response/OrderResponse.<ext>
│     └─ OrderExceptionHandler.<ext>
├─ application/
│  └─ order/
│     ├─ port/in/PlaceOrderUseCase.<ext>
│     ├─ port/out/SaveOrderPort.<ext>
│     ├─ port/out/PaymentGateway.<ext>
│     ├─ service/PlaceOrderService.<ext>
│     ├─ command/PlaceOrderCommand.<ext>
│     ├─ result/PlaceOrderResult.<ext>
│     └─ exception/OrderConflict.<ext>
├─ domain/
│  └─ order/
│     ├─ model/Order.<ext>
│     ├─ model/OrderId.<ext>
│     ├─ policy/OrderPolicy.<ext>
│     ├─ event/OrderPlaced.<ext>
│     └─ exception/OrderRejected.<ext>
├─ infrastructure/
│  ├─ persistence/order/
│  │  ├─ OrderJpaEntity.<ext>
│  │  ├─ OrderJpaRepository.<ext>
│  │  ├─ OrderPersistenceMapper.<ext>
│  │  └─ OrderPersistenceAdapter.<ext>
│  └─ client/payment/PaymentClientAdapter.<ext>
└─ config/ApplicationConfig.<ext>
```

- 허용 방향은 `presentation → application → domain`,
  `infrastructure → application/domain`, `config → 모든 조립 대상`입니다.
- domain은 application, presentation, infrastructure와 framework를 참조하지 않습니다.
- application은 input/output port를 소유하고 concrete repository/client를 참조하지
  않습니다.
- infrastructure adapter가 application output port를 구현합니다. persistence mapping에
  domain type이 필요할 때만 domain을 참조합니다.
- config 또는 composition root가 input adapter, use case와 output adapter를 조립합니다.

## 3. DTO와 오류 위치

| 종류 | 샘플 파일 | 소유권 |
| --- | --- | --- |
| Request DTO | `presentation/order/request/CreateOrderRequest.<ext>` | transport validation과 역직렬화 |
| Response DTO | `presentation/order/response/OrderResponse.<ext>` | 공개 응답 schema와 serialization |
| Command/result | `application/order/{command,result}/PlaceOrder*.<ext>` | use case 입출력 |
| Input/output port | `application/order/port/{in,out}/*.<ext>` | 호출·외부 의존 계약 |
| Domain model/error | `domain/order/model/Order.<ext>`, `exception/OrderRejected.<ext>` | 불변식과 업무 거절 |
| Persistence model | `infrastructure/persistence/order/OrderJpaEntity.<ext>` | ORM/database mapping |
| Infrastructure error | `OrderPersistenceAdapter.<ext>` 또는 client adapter 내부 | 기술 원인 보존과 application 오류 변환 |
| 외부 error response | `presentation/order/OrderExceptionHandler.<ext>` | 내부 오류를 transport 계약으로 변환 |

application이 request DTO를 받거나 domain entity를 response로 반환하지 않습니다. 단순
조회에서 command/result가 의미를 추가하지 않는다면 명확한 query/result type 하나를
사용할 수 있으며 계층마다 같은 type을 복제하지 않습니다.

## 4. 샘플 요청 흐름

```text
CreateOrderRequest
  → OrderController
  → PlaceOrderCommand
  → PlaceOrderUseCase
  → PlaceOrderService
  → Order.place(...)
  → SaveOrderPort
  → OrderPersistenceAdapter
  → PlaceOrderResult
  → OrderResponse
```

1. presentation은 input port/use case만 호출합니다.
2. application service가 transaction과 port 호출 순서를 소유합니다.
3. domain은 framework 없이 불변식과 상태 전이를 수행합니다.
4. infrastructure가 persistence/client 세부사항을 처리하고 원인을 보존합니다.
5. exception handler가 domain/application 오류를 외부 error response로 변환합니다.

## 5. 경계와 검증

- controller가 output port, repository, ORM entity와 domain service를 직접 호출하지 않게
  합니다.
- input port는 여러 delivery adapter가 안정된 contract에 의존할 때만 둡니다. 모든
  service 앞에 interface를 만들지 않습니다.
- persistence model과 domain model의 차이가 작으면 adapter에서 같은 객체를 사용할 수
  있지만 ORM 제약이 domain API를 왜곡하면 분리합니다.
- package 의존 방향과 금지 import를 architecture test 또는 정적 분석으로 검증합니다.
- 위 허용 방향과 샘플 요청 흐름은 실제 build module 그래프와 구분합니다. 프로젝트의
  모듈 diagram에는 범위와 화살표 의미를 명시하고 build tool의 실제 의존 그래프와
  일치하는지 검사합니다. 문서 일치 검사와 금지 방향·순환 의존 검사는 별도로 유지합니다.
- 작은 CRUD는 파일을 합칠 수 있지만 presentation/application/domain/infrastructure의
  논리적 책임은 유지합니다.

## 6. 피해야 할 구조

- controller가 repository/ORM entity를 직접 호출하거나 transaction을 소유
- request DTO를 application/domain까지 전달하거나 domain error에 HTTP status 포함
- domain에 Spring Data, Pydantic, HTTP request 같은 framework type 노출
- input/output port와 mapper를 실제 경계 없이 모든 class에 기계적으로 생성
- application service가 단순 위임만 하고 흐름이 controller와 entity에 분산
- `common`과 `base` package가 계층 의존 규칙을 우회
