# React + TypeScript Guidelines & Standards

React 코드는 render를 순수한 UI 계산으로 유지하고, 최소 상태에서 화면을 파생하며,
Effect는 외부 시스템과의 동기화에만 사용해야 합니다. component는 화면 구조와 상태
소유권을 반영하고 TypeScript는 불가능한 UI 상태를 줄이는 데 사용합니다.

## 1. React다운 기본 원칙

- component는 같은 props와 state에서 같은 JSX를 반환하는 순수 render를 유지합니다.
- UI를 책임 단위 component로 분해하되 JSX 몇 줄마다 추출하지 않습니다. 독립적인 의미,
  재사용, 상태 경계 또는 테스트 경계가 있을 때 추출합니다.
- 상속보다 component composition과 children/slot pattern을 사용합니다.
- render 중 prop, state, module global과 외부 객체를 mutate하지 않습니다.
- component 이름은 역할을, event handler는 사용자 의도 또는 domain event를 드러냅니다.

## 2. React 적용: feature-sliced

React를 선택하고 `architectures`를 생략하면 `feature-sliced` profile을 함께 적용합니다.
React 구조의 중심은 component tree, 상태 소유권과 단방향 data flow입니다. 규모가 있는
application은 Feature-Sliced Design 계층을 기본으로 사용하고 각 계층 아래를 business
slice로 나눕니다.

```text
src/
├─ app/
│  ├─ providers/                # QueryClient, theme, router provider
│  ├─ router/                   # route, authorization, lazy boundary
│  └─ styles/                   # global style과 design token
├─ pages/<route>/ui/            # route와 URL state, 화면 조립
├─ widgets/<workspace>/ui/      # page-level 복합 화면과 상세 panel
├─ features/<verb-noun>/
│  ├─ model/                    # form state와 feature validation
│  └─ ui/                       # 사용자 action과 form
├─ entities/<noun>/
│  ├─ api/                      # transport, query key, cache helper
│  ├─ model/                    # resource type과 순수 계산
│  └─ ui/                       # entity 자체의 작은 표현
├─ shared/
│  ├─ api/                      # 공통 HTTP와 error contract
│  ├─ config/                   # 환경과 theme 설정
│  ├─ lib/                      # domain 비의존 utility
│  └─ ui/{primitives,patterns,feedback}/
├─ stories/                     # foundation과 공용 조합 story
└─ test/                        # 전역 test setup
```

- 의존 방향은 `app → pages → widgets → features → entities → shared`입니다. 상위 계층은
  필요한 하위 계층을 건너뛰어 직접 사용할 수 있지만 하위 계층은 상위 계층을 import하지
  않습니다.
- `app`은 provider, router와 전역 style을, `pages`는 route/URL state를, `widgets`는 여러
  entity와 feature의 화면 조합을 소유합니다. `features`는 사용자 행위, `entities`는 API
  resource와 query key, `shared`는 domain 비의존 기반을 소유합니다.
- 같은 계층의 slice 간 참조는 실제 business 관계가 있고 순환하지 않을 때만 허용합니다.
  내부 import는 프로젝트가 정한 root alias를 사용해 깊은 상대 경로와 표기 혼용을 막습니다.
- server state, URL state와 local UI state의 소유자를 구분합니다. 여러 child가 사용하는
  local state는 가장 가까운 공통 parent가 소유하고 값과 event를 아래로 전달합니다.
- component 분리는 폴더 규칙이 아니라 UI 책임, 독립 상태와 변경 이유를 기준으로 합니다.
  작은 화면에 모든 directory를 미리 만들지 않습니다.

```tsx
// pages: route 화면 조립
import { ProjectWorkspace } from "@/widgets/project-workspace/ui/ProjectWorkspace";

// widgets: entity 조회와 사용자 action 조합
import { ProjectForm } from "@/features/manage-project/ui/ProjectForm";
import { getProject } from "@/entities/project/api/projectApi";
import { Dialog } from "@/shared/ui/primitives/dialog/Dialog";
```

예시 흐름은 `AppRouter → ProjectsPage → ProjectWorkspace → ProjectForm → projectApi →
shared/http`입니다. route, 복합 화면, 행위, resource와 transport 책임이 계층마다 한 번씩
나타나야 하며 page나 widget이 공통 HTTP client를 다시 만들지 않습니다.

## 3. Props와 상태 모델링

- state는 사용자가 바꾸며 render 사이에 기억해야 하는 최소 값만 저장합니다. props나
  기존 state에서 계산 가능한 값은 render 중 파생합니다.
- 서로 모순될 boolean state 여러 개는 discriminated union이나 reducer state로
  모델링합니다.
- state를 소유해야 하는 가장 가까운 공통 조상에 두고 필요 이상으로 global store로
  올리지 않습니다.
- props는 구현 편의보다 component가 지원하는 유효한 variant를 표현합니다.

```tsx
type DialogState =
  | { status: "closed" }
  | { status: "editing"; orderId: string }
  | { status: "saving"; orderId: string };

interface OrderDialogProps {
  readonly state: DialogState;
  readonly onClose: () => void;
}
```

## 4. Hook과 Effect

- Hook은 component 또는 custom Hook 최상위에서 같은 순서로 호출합니다. 조건문,
  반복문과 일반 callback 안에서 호출하지 않습니다.
- Effect는 network connection, DOM API, timer, external widget처럼 React 밖의 시스템과
  동기화할 때 사용합니다.
- props/state에서 값을 계산하거나 click에 반응하기 위해 Effect를 사용하지 않습니다.
  render 중 계산하거나 event handler에서 처리합니다.
- dependency를 빼서 lint를 회피하지 않습니다. Effect가 자주 재실행되면 object/function
  identity와 책임 경계를 수정합니다.
- setup이 만든 subscription, observer, timer와 request는 cleanup에서 해제하거나
  취소합니다.

## 5. Event와 비동기 UI

- 사용자 동작의 side effect는 해당 event handler에 둡니다.
- request 상태는 idle/loading/success/error처럼 명시적으로 모델링하고 중복 submit,
  stale response와 unmount 이후 결과를 처리합니다.
- framework나 data library가 제공하는 server-state 기능이 있으면 직접 Effect 기반
  fetching을 중복 구현하지 않습니다.
- optimistic update는 rollback과 server authoritative response 병합 정책을 함께
  설계합니다.
- render 중 발생하는 실패는 route 또는 독립적으로 복구 가능한 feature 단위의 Error
  Boundary에서 fallback, retry와 관찰 경계를 제공합니다. event handler와 일반 async
  callback 오류까지 Error Boundary가 처리한다고 가정하지 않습니다.

## 6. Rendering과 성능

- list key는 sibling 사이에서 안정적인 identity를 사용합니다. 배열 index나 매 render
  생성되는 값으로 state identity를 깨지 않습니다.
- `useMemo`, `useCallback`, `memo`는 correctness 도구가 아닙니다. profiler로 비용이나
  불필요한 render를 확인한 뒤 적용합니다.
- state를 지나치게 높은 곳에 두거나 모든 context 값을 하나의 큰 object로 제공해 넓은
  re-render를 만들지 않습니다.
- 큰 작업은 component 분리, data shape 개선과 계산 위치 조정을 먼저 검토합니다.

## 7. Form과 접근성

- input은 controlled 또는 uncontrolled 소유 모델을 일관되게 선택하고 lifecycle 중간에
  바꾸지 않습니다.
- button, link, label, heading 같은 semantic element를 먼저 사용하고 `div`에 click과
  role을 덧붙이는 방식은 피합니다.
- validation error는 field와 연결하고 keyboard focus, loading/disabled, empty/error
  상태를 함께 설계합니다.
- custom component가 DOM prop, `aria-*`, ref를 올바르게 전달하는지 검증합니다.

## 8. 테스트와 품질 게이트

- 내부 Hook 호출이나 class name보다 사용자가 보는 role, label, text와 상호작용을
  검증합니다.
- loading, empty, error, permission과 async race 시나리오를 포함합니다.
- test와 story는 검증하는 slice 가까이에 두고, 공용 foundation story와 전역 setup만
  `stories/`, `test/`에 둡니다.
- reusable UI는 Storybook이 있으면 주요 state와 viewport를 story로 고정합니다.
- root alias 검증과 계층 import rule을 CI에 연결하여 하위 계층의 역방향 import와 순환
  의존을 차단합니다.
- 단일 저장소는 `tools/frameworks/react-ts/dependency-cruiser.md`, Nx workspace는
  `@nx/enforce-module-boundaries`, 기존 ESLint 중심 경계는 `eslint-plugin-boundaries` 중 하나를
  아키텍처 규칙의 원본으로 선택합니다.
- `tools/frameworks/react-ts/eslint-plugin-react-hooks.md`를 일반 TypeScript ESLint 설정과
  함께 적용해 Hook 호출 순서와 Effect dependency를 정적 검증합니다.
- `tools/frameworks/react-ts/vitest.md`에 따라 V8 coverage 대상과 threshold를 설정하고
  `test:coverage`를 필수 CI 경로에 연결합니다.

```sh
pnpm run imports:check
pnpm run test:coverage
pnpm run test-storybook
pnpm run build
```

## 9. React 안티패턴

- Effect로 derived state를 복사하고 동기화
- boolean prop과 state가 늘어나 불가능한 UI 조합 생성
- index key, render 중 mutation, cleanup 없는 subscription
- 측정 없이 모든 callback과 계산을 memoization

참고: [Thinking in React](https://react.dev/learn/thinking-in-react),
[Choosing the State Structure](https://react.dev/learn/choosing-the-state-structure),
[You Might Not Need an Effect](https://react.dev/learn/you-might-not-need-an-effect),
[Rules of Hooks](https://react.dev/reference/rules/rules-of-hooks),
[Error Boundaries](https://react.dev/reference/react/Component#catching-rendering-errors-with-an-error-boundary)
