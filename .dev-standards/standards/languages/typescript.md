# TypeScript Guidelines & Standards

TypeScript 코드는 JavaScript runtime의 동작을 유지하면서 타입 좁히기와 구조적 typing으로
잘못된 상태를 줄여야 합니다. 타입 선언은 외부 입력을 검증하지 않으므로 compile-time
모델과 runtime parser의 책임을 분리합니다.

## 1. TypeScript다운 기본 원칙

- lockfile의 TypeScript 버전과 `target`, `lib`, `module`, `moduleResolution`, 실제 Node/
  browser 지원 범위를 함께 확인합니다. 해당 버전의 공식 권장 옵션과 module 규칙을 따르고
  bundler, framework와 type-aware ESLint의 지원 범위도 맞춥니다.
- `satisfies`는 TypeScript 4.9 이상에서 지원됩니다. `target`의 문법 변환과 `lib`의 타입
  선언은 runtime API polyfill을 제공하지 않으므로 실제 배포 환경에서도 동작을 검증합니다.

- `strict`를 기본으로 하고 저장소의 지원 runtime, module 방식과 build output을
  `tsconfig.json`에 명시합니다.
- local 구현은 inference를 활용하고 exported function, package boundary와 복잡한 반환은
  의도적인 타입을 선언합니다.
- `any` 대신 `unknown`에서 시작해 type guard 또는 parser로 좁힙니다.
- class hierarchy보다 object composition, function과 discriminated union을 우선합니다.
  runtime identity와 encapsulated mutable state가 필요한 경우에는 class가 적합합니다.
- `as`는 이미 검증된 사실을 compiler에 전달할 때만 사용합니다. 이중 단언으로 모델
  불일치를 숨기지 않습니다.

## 2. 타입으로 상태를 모델링한다

- 함께 존재할 수 없는 boolean flag 집합은 discriminated union으로 표현합니다.
- `never`를 사용한 exhaustive check로 새 상태가 추가됐을 때 처리 누락을 compiler가
  찾게 합니다.
- literal 정보를 보존해야 하는 설정에는 `as const`와 `satisfies`를 사용하되 둘의
  narrowing 효과를 이해하고 선택합니다.
- `interface`는 확장 가능한 object contract, `type`은 union, tuple과 type 조합에
  자연스럽습니다. 일률적인 금지 규칙보다 공개 확장 의도를 기준으로 선택합니다.
- enum runtime object가 필요하지 않으면 literal union 또는 `as const` object를
  우선합니다. 이미 공개된 enum을 미관상 변경하지 않습니다.

```typescript
type LoadState<T> =
  | { status: "idle" }
  | { status: "loading" }
  | { status: "success"; data: T }
  | { status: "error"; error: Error };

function assertNever(value: never): never {
  throw new Error(`Unhandled state: ${JSON.stringify(value)}`);
}

function label<T>(state: LoadState<T>): string {
  switch (state.status) {
    case "idle": return "idle";
    case "loading": return "loading";
    case "success": return "ready";
    case "error": return state.error.message;
    default: return assertNever(state);
  }
}
```

## 3. Runtime 경계와 데이터 소유권

- JSON, 환경변수, storage, query parameter는 `unknown`으로 받고 schema library 또는
  명시적인 parser를 통과시킵니다.
- `null`, `undefined`와 optional property의 부재를 같은 상태로 간주할지 경계 계약으로
  정합니다. postfix `!`는 runtime 검증을 만들지 않으므로 type guard나 parser로 확인할 수
  있는 값을 대신 단언하는 데 사용하지 않습니다.
- optional chaining은 중간 값의 부재가 실제로 허용될 때 사용합니다. 반드시 존재해야 하는
  값에 `?.`를 연쇄해 contract 위반을 조용한 `undefined`로 바꾸지 않습니다.
- 기본값은 `0`, `false`, 빈 문자열을 보존해야 하면 `??`를 사용하고 모든 falsy 값을 같은
  부재로 취급하는 경우에만 `||`를 사용합니다.
- domain model과 wire DTO를 구분하고 날짜, bigint, enum-like value의 직렬화 차이를
  경계에서 변환합니다.
- API 입력에는 가능한 한 `readonly` collection과 property를 사용해 mutation 권한을
  드러냅니다. 성능 때문에 내부 mutation을 사용할 수 있지만 외부에 공유하지 않습니다.
- object spread는 prototype, non-enumerable field와 깊은 복사를 보존하지 않습니다.
  class instance나 중첩 mutable object를 무조건 `{ ...value }`로 복제하지 않습니다.

```typescript
interface OrderPayload {
  readonly id: string;
  readonly total: number;
}

function parseOrder(value: unknown): OrderPayload {
  if (typeof value !== "object" || value === null) {
    throw new TypeError("invalid order payload");
  }
  const candidate = value as Record<string, unknown>;
  if (typeof candidate.id !== "string" || typeof candidate.total !== "number") {
    throw new TypeError("invalid order payload");
  }
  return { id: candidate.id, total: candidate.total };
}
```

## 4. 함수, module과 비동기 흐름

- boolean flag 여러 개보다 option object 또는 union으로 호출 의도를 표현합니다.
- 독립적인 비동기 작업만 `Promise.all`로 병렬화합니다. 순서 의존성, rate limit,
  transaction 경계를 깨지 않습니다.
- fire-and-forget Promise에는 명시적인 오류 관찰과 lifecycle을 둡니다. 취소 가능한 API는
  `AbortSignal`을 경계까지 전달합니다.
- `import type`으로 runtime dependency와 type dependency를 구분합니다.
- barrel export는 package 공개 API에는 유용하지만 내부 module 전체에 순환 의존과 bundle
  증가를 만들 수 있으므로 사용 범위를 제한합니다.

## 5. 오류 모델

- programmer error와 예상하지 못한 인프라 실패는 `Error` subclass와 cause를 보존해
  전달합니다.
- 실패가 정상적인 domain 결과라면 discriminated union 결과를 고려합니다. 예외와
  result union을 동일 계층에서 무작위로 섞지 않습니다.
- `catch` 값은 `unknown`으로 처리하고 `instanceof Error` 등으로 좁힙니다.
- 오류를 빈 배열이나 `undefined`로 바꿔 성공과 구분할 수 없게 만들지 않습니다.

## 6. TSDoc과 주석

- exported API와 확장 지점은 `/** ... */`로 의미, 단위, side effect와 실패 조건을
  설명합니다.
- TypeScript가 이미 표현하는 타입을 `@param {string}`처럼 반복하지 않습니다.
- 일반 주석은 browser/runtime 제약, protocol 호환성, 비직관적인 결정 이유를 기록합니다.

```typescript
/**
 * 현재 tenant에서 볼 수 있는 주문을 조회합니다.
 *
 * @throws {@link OrderNotFoundError} 주문이 없거나 접근할 수 없는 경우
 */
export async function loadOrder(orderId: OrderId): Promise<Order> {
  // 구현 내용
}
```

## 7. 테스트와 품질 게이트

- Java/Kotlin 생태계 도구를 이름이 아니라 책임으로 대응시킵니다.

  | Java/Kotlin 생태계 | React / TypeScript 대응 | 역할 |
  | --- | --- | --- |
  | Detekt / PMD | ESLint | 정적 분석, 코드 품질, 버그 패턴 탐지 |
  | Checkstyle / ktlint | ESLint + Prettier | 코딩 컨벤션 + 포맷팅 |
  | ArchUnit | dependency-cruiser / eslint-plugin-boundaries / Nx module boundaries | 아키텍처/레이어 의존성 검증 |
  | SpotBugs | ESLint + TypeScript compiler | 잠재 버그/타입 오류 |
  | JaCoCo / Kover | Vitest/Jest + V8/Istanbul coverage | 테스트 커버리지 |

- 이 표는 책임 대응이며 분석 단계가 같은 것은 아닙니다. TypeScript compiler는 JVM bytecode
  분석을 대체하지 않고, `strict` typecheck와 type-aware ESLint를 조합해 유사한 오류 범위를
  앞단에서 차단합니다.
- compiler가 확인하는 타입 모양만 다시 테스트하지 말고 runtime parser, union 분기,
  async 실패와 공개 동작을 검증합니다.
- type-level contract가 중요한 library는 compile fixture 또는 type test를 별도로 둡니다.
- React 전용 architecture와 coverage 도구 선택은 `../frameworks/react-ts.md`를 따릅니다.
- package script를 `mise run verify`의 단일 진입점에 연결합니다.

```sh
pnpm install --frozen-lockfile
pnpm run format:check
pnpm run lint
pnpm run typecheck
pnpm run imports:check
pnpm run test:coverage
pnpm run build
```

시작점은 `templates/mise/typescript/`, `templates/pnpm/`, `templates/prettier/`,
`templates/biome/`에 있습니다. formatter는 Prettier 또는 Biome 하나만 선택합니다.

## 8. 다른 언어 습관을 옮기지 않는다

- Java/C#식 nominal hierarchy, DTO class와 getter/setter를 기본 구조로 복제하지 않습니다.
- 타입 단언이나 generated declaration이 runtime validation을 대신한다고 간주하지
  않습니다.
- 모든 코드를 `map/reduce` 한 줄로 압축하거나 모든 object를 깊은 불변으로 만드는 것을
  TypeScript다운 코드로 오해하지 않습니다.

참고: [Narrowing](https://www.typescriptlang.org/docs/handbook/2/narrowing.html),
[Object Types](https://www.typescriptlang.org/docs/handbook/2/objects.html),
[TSConfig strict](https://www.typescriptlang.org/tsconfig/strict.html),
[Null and undefined](https://www.typescriptlang.org/docs/handbook/2/everyday-types.html#null-and-undefined)

버전별 설정 참고: [TypeScript 4.9](https://www.typescriptlang.org/docs/handbook/release-notes/typescript-4-9.html),
[TSConfig target](https://www.typescriptlang.org/tsconfig/target.html),
[TSConfig lib](https://www.typescriptlang.org/tsconfig/lib.html)
