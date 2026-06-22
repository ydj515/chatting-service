# Phase 7 Takeover Smoke Routing Check Integration 설계서

- 작성일: 2026-06-21
- 범위: Phase 7 본체 두 번째 작은 슬라이스
- 대상: `phase6-fanout-takeover-smoke.mjs` restore 이후 role routing opt-in 검증

## 1. 문제 이해 / 요구사항 정리

### 조건

- 첫 번째 Phase 7 슬라이스에서 `scripts/phase7-role-routing-check.mjs`를 추가했다.
- 이 check는 `/health`, `/api/ws-tickets`, `/api/admin/health`, `/api/ws/chat?...`를 role-specific하게 검증한다.
- 2026-06-20 사전 점검에서 Nginx stale upstream 문제는 `phase6-fanout-takeover-smoke.mjs`가 worker scale restore를 수행한 뒤 재현되었다.
- `phase6-fanout-takeover-smoke.mjs`의 본래 목적은 fanout owner kill takeover 검증이다.
- 모든 Phase 6 smoke 실행에 Nginx routing check를 강제하면 기존 검증이 더 무거워지고 Nginx 의존성이 생긴다.
- 사용자는 Phase 7용 opt-in 옵션으로 연결하기를 선택했다.

### 목표

- `phase6-fanout-takeover-smoke.mjs`에 Phase 7용 opt-in routing check 옵션을 추가한다.
- 기본 동작은 바꾸지 않는다.
- `--verify-routing-after-restore`가 있을 때만 worker restore 이후 `phase7-role-routing-check.mjs`를 실행한다.
- routing check 실패는 takeover smoke 실패로 전파하되, 실패 원인이 routing check임을 stderr와 JSON summary로 분리한다.
- restore가 실제로 수행된 경우에만 routing check를 실행한다.
- restore 자체가 실패한 경우에는 routing check를 억지로 실행하지 않는다.

## 2. 해결 접근

### 선택한 접근

`phase6-fanout-takeover-smoke.mjs`에 opt-in CLI 옵션을 추가하고, `finally`의 restore 성공 직후 routing check를 실행한다.

옵션:

```text
--verify-routing-after-restore
--routing-check-base-url http://localhost
--routing-check-admin-token test
--routing-check-timeout-ms 3000
```

환경 변수 fallback:

```text
CHAT_PHASE7_BASE_URL
CHAT_ADMIN_TOKEN
CHAT_PHASE7_ROUTE_TIMEOUT_MS
```

CLI 인자가 환경 변수보다 우선한다.

### 대안 비교

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| opt-in 옵션으로 `phase6` smoke에 연결 | 문제 재현 지점인 restore 직후를 자동 검증한다 | 옵션과 테스트가 조금 늘어난다 | 기본안 |
| 별도 wrapper script 작성 | `phase6` 스크립트를 덜 건드린다 | 실제 `finally` restore 직후 맥락이 약해진다 | 보조 대안 |
| 항상 routing check 실행 | 놓칠 가능성이 가장 낮다 | Phase 6 smoke가 Nginx 의존성을 강제로 갖는다 | 사용하지 않음 |
| 수동 실행 유지 | 구현 변경이 없다 | stale upstream 문제를 다시 놓칠 수 있다 | 사용하지 않음 |

## 3. 설계

### 3.1 파일 구조

- `scripts/lib/phase6TakeoverSmokePlan.mjs`
  - `parseTakeoverSmokeArgs`에 opt-in routing check 옵션을 추가한다.
  - `buildRoutingCheckArgs(options)`를 추가해 child process 인자 구성을 테스트 가능하게 한다.
- `scripts/lib/phase6TakeoverSmokePlan.test.mjs`
  - 기본값이 opt-in disabled인지 검증한다.
  - CLI 옵션이 routing check 설정으로 매핑되는지 검증한다.
  - `buildRoutingCheckArgs`가 `phase7-role-routing-check.mjs` 인자를 정확히 만드는지 검증한다.
- `scripts/phase6-fanout-takeover-smoke.mjs`
  - `buildRoutingCheckArgs`를 import한다.
  - `finally`에서 worker restore 성공 후 `options.verifyRoutingAfterRestore`가 true이면 routing check를 실행한다.
  - routing check 실패 시 `phase7 routing check after restore failed` 메시지를 포함해 실패한다.
- `docs/phase7_nginx_role_routing_check.md`
  - takeover smoke와 함께 실행하는 명령을 추가한다.
- `docs/phase7_slices.md`
  - 두 번째 슬라이스를 독립 항목으로 추가한다.

### 3.2 실행 예시

```bash
node scripts/phase6-fanout-takeover-smoke.mjs \
  --room phase7-routing \
  --viewers 3 \
  --messages-per-sec 20 \
  --duration 20 \
  --kill-after 5 \
  --verify-routing-after-restore \
  --routing-check-base-url http://localhost \
  --routing-check-admin-token "${CHAT_ADMIN_TOKEN:-test}"
```

### 3.3 성공/실패 처리

- takeover load가 실패하면 기존처럼 실패한다.
- worker restore가 실패하면 routing check를 실행하지 않고 restore 실패를 그대로 전파한다.
- worker restore가 성공하고 opt-in이 켜져 있으면 routing check를 실행한다.
- routing check가 exit code `0`이면 smoke는 기존 summary를 출력한 뒤 정상 종료한다.
- routing check가 non-zero이면 smoke는 실패하고 stderr에 routing check 실패임을 명시한다.

### 3.4 JSON Summary 정책

이번 슬라이스에서는 `phase6-fanout-takeover-smoke.mjs`의 최종 JSON summary 구조를 크게 바꾸지 않는다. routing check 성공 여부는 child process의 JSON output을 그대로 stdout/stderr에 흘려 운영자가 확인하게 한다.

summary 구조 확장은 다음 fanout owner kill takeover summary 슬라이스에서 raw delivery/client-visible 결과 분리와 함께 다룬다.

## 4. 테스트 전략

TDD 순서:

1. `parseTakeoverSmokeArgs` 기본값 테스트를 추가한다.
2. opt-in CLI 옵션 파싱 테스트를 추가하고 실패를 확인한다.
3. `buildRoutingCheckArgs` 테스트를 추가하고 실패를 확인한다.
4. parser와 builder를 구현한다.
5. `phase6-fanout-takeover-smoke.mjs` 문법 검사를 실행한다.
6. 실제 routing check 단독 실행을 다시 검증한다.

검증 명령:

```bash
node --test scripts/lib/phase6TakeoverSmokePlan.test.mjs
node --test scripts/lib/phase7RoleRoutingCheckPlan.test.mjs
node --check scripts/phase6-fanout-takeover-smoke.mjs
node scripts/phase7-role-routing-check.mjs --base-url http://localhost --admin-token "${CHAT_ADMIN_TOKEN:-test}"
git diff --check
```

실제 owner kill smoke는 시간이 길고 worker scale 상태에 영향을 주므로, 이번 슬라이스의 기본 검증에서는 문법/유틸/단독 routing check를 release gate로 둔다. 필요 시 수동으로 opt-in 전체 smoke를 실행한다.

## 5. 복잡도

- 인자 파싱 시간 복잡도: `O(A)`
- routing check 인자 생성 시간 복잡도: `O(1)`
- restore 이후 routing check 실행 시간 복잡도: `O(R)`
- 공간 복잡도: `O(R)`

여기서 `A`는 CLI 인자 수, `R`은 routing check route 수다. 현재 기본 `R=4`다.

## 6. 주의사항

> - opt-in 기본값은 반드시 false다. Phase 6 takeover smoke의 기존 사용성을 깨지 않기 위함이다.
> - restore가 실패한 상태에서 routing check를 실행하면 원인 분리가 흐려지므로 실행하지 않는다.
> - routing check 실패는 fanout takeover 실패가 아니라 Phase 7 Nginx routing release gate 실패로 해석한다.
> - `CHAT_ADMIN_TOKEN` 원문은 command log나 summary에 직접 출력하지 않는다.
> - 최종 summary 구조 확장은 이번 슬라이스 범위가 아니다. 다음 takeover summary 슬라이스에서 다룬다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| opt-in 연결 | restore 직후 stale upstream을 놓치지 않고 기존 smoke 기본 동작도 보존한다 | 옵션이 늘어난다 | 채택 |
| wrapper script | 실행 조합을 외부로 분리할 수 있다 | restore 직후 자동성 약함 | 필요 시 추가 |
| 항상 실행 | 사람이 옵션을 빼먹을 일이 없다 | Nginx 없는 Phase 6 검증이 깨질 수 있다 | 제외 |

## 8. 후속 질문

- 다음 슬라이스에서 owner kill takeover summary에 routing check 결과까지 포함할 것인가?
- opt-in 전체 smoke를 CI에 넣을 것인가, 수동 운영 smoke로 둘 것인가?
