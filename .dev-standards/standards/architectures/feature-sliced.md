# Feature-Sliced Frontend Architecture Guidelines & Standards

Feature-sliced 구조는 React 애플리케이션을 UI 조립 수준과 business slice로 나눕니다.
React 자체가 directory layout을 강제하지는 않지만, 이 저장소에서는 규모가 있는 React
SPA의 기본 profile로 사용합니다.

## 1. 적용 기준

- route, 복합 화면, 사용자 행위와 API entity의 변경 이유가 구분됩니다.
- 여러 팀이나 기능이 같은 UI/API 기반을 사용하면서 역방향 import와 순환 의존을
  통제해야 합니다.
- server state, URL state와 local UI state의 소유자를 명시할 필요가 있습니다.
- 작은 화면은 불필요한 `widgets`나 `entities` 계층을 만들지 않고 필요한 slice부터
  시작합니다.

## 2. 기본 구조와 의존 방향

```text
src/
├─ app/
│  ├─ errors/AppErrorBoundary.tsx
│  ├─ providers/AppProviders.tsx
│  ├─ router/AppRouter.tsx
│  └─ styles/global.css
├─ pages/orders/ui/OrdersPage.tsx
├─ widgets/order-workspace/ui/OrderWorkspace.tsx
├─ features/create-order/
│  ├─ api/createOrder.ts
│  ├─ model/createOrderSchema.ts
│  ├─ model/createOrderState.ts
│  └─ ui/CreateOrderForm.tsx
├─ entities/order/
│  ├─ api/orderDto.ts
│  ├─ api/parseOrder.ts
│  ├─ api/orderApi.ts
│  ├─ model/order.ts
│  └─ ui/OrderCard.tsx
└─ shared/
   ├─ api/httpClient.ts
   ├─ api/ApiError.ts
   ├─ lib/assertNever.ts
   └─ ui/Button.tsx
```

- 의존 방향은 `app → pages → widgets → features → entities → shared`입니다.
- 상위 계층은 필요한 하위 계층을 건너뛰어 사용할 수 있지만 하위 계층은 상위 계층을
  import하지 않습니다.
- 같은 계층의 slice 간 참조는 공개 API를 통하고 순환하지 않아야 합니다.
- `shared`는 domain을 모르며 feature status, API DTO와 business label을 소유하지
  않습니다.

## 3. DTO와 오류 위치

| 종류 | 샘플 파일 | 소유권 |
| --- | --- | --- |
| Query response DTO | `entities/order/api/orderDto.ts` | wire field와 runtime parser 입력 |
| DTO parser/mapper | `entities/order/api/parseOrder.ts` | 외부 payload를 entity model로 변환 |
| Mutation request | `features/create-order/api/createOrder.ts` | 사용자 행위별 API contract |
| Entity/value model | `entities/order/model/order.ts` | transport와 독립적인 UI/domain 표현 |
| Form/input state | `features/create-order/model/createOrderState.ts` | validation, submit과 예상 거절 |
| Transport error | `shared/api/ApiError.ts` | timeout, network와 protocol 실패 |
| Render error boundary | `app/errors/AppErrorBoundary.tsx` 또는 page 인접 경계 | 복구 가능한 화면 범위와 fallback |

generated DTO를 component props와 form state로 직접 퍼뜨리지 않습니다. wire와 내부 model의
의미·변경 주기가 실제로 같다면 의미 없는 mapper를 만들지 않고 runtime validation
경계만 유지할 수 있습니다.

## 4. 샘플 요청 흐름

```text
CreateOrderForm
  → createOrderSchema
  → features/create-order/api/createOrder
  → shared/api/httpClient
  → orderDto
  → parseOrder
  → entities/order/model/order
  → createOrderState(success | rejected | error)
```

1. feature UI가 사용자 입력을 feature schema/state로 관리합니다.
2. mutation adapter가 wire request를 만들고 공통 HTTP client를 호출합니다.
3. response DTO는 entity parser를 거쳐 내부 model로 변환됩니다.
4. 예상 가능한 거절은 discriminated union state로, transport 실패는 `ApiError`로
  구분합니다.
5. page/widget이 entity와 feature 결과를 조립합니다.

## 5. 경계와 검증

- root alias와 import rule로 역방향 의존과 slice 순환을 차단합니다.
- loading, empty, rejected, transport error와 render error를 서로 다른 UI 상태로
  검증합니다.
- Error Boundary는 render/lifecycle 실패를 담당하며 event handler와 일반 async callback
  오류를 자동 처리한다고 가정하지 않습니다.
- test와 story는 검증하는 slice 가까이에 두고 전역 setup만 공용 위치에 둡니다.
- 작은 애플리케이션은 계층을 생략할 수 있지만 생략한 책임을 `shared`로 내리지 않습니다.

## 6. 피해야 할 구조

- 모든 component를 `components/`, 모든 request를 `services/`에 평면적으로 누적
- generated DTO를 component props와 form state로 직접 확산
- 모든 실패를 전역 `exceptions/` 또는 하나의 toast handler로 축소
- 재사용 근거 없는 component를 shared로 승격
- 의미 없는 slice와 barrel export를 directory 규칙 때문에 생성
- feature가 page/router를 import하거나 shared가 domain model을 참조

참고: [Thinking in React](https://react.dev/learn/thinking-in-react),
[Sharing State Between Components](https://react.dev/learn/sharing-state-between-components)
