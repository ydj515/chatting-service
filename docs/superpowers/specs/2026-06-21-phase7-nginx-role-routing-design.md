# Phase 7 Nginx Role Routing Synthetic Check 설계서

- 작성일: 2026-06-21
- 범위: Phase 7 본체 첫 슬라이스
- 대상: Docker Compose 환경의 Nginx role routing 검증과 대응 절차

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 6은 메시지 수락, 저장, fan-out, admin search, hot room downgrade 정책을 구현한 상태다.
- Phase 7은 새 사용자 기능이 아니라 운영 검증, 계측, 부하/장애 테스트, release gate 단계다.
- Phase 7 첫 작업은 `Nginx stale upstream` 대응이다.
- `infra/nginx/nginx.conf`는 `chat-api-app-1`, `chat-api-app-2`, `chat-websocket-app-1`, `chat-websocket-app-2`, `chat-admin-app-1`을 정적 upstream으로 참조한다.
- Docker Compose에서 app container가 recreate/scale 된 뒤 Nginx가 예전 upstream IP를 계속 사용하면 `/api/`, `/api/ws/`, `/api/admin/` 요청이 잘못된 role로 라우팅될 수 있다.
- 2026-06-20 사전 점검에서 `phase6-fanout-takeover-smoke.mjs` 복구 과정 후 `POST /api/users/register`가 404를 반환했고, Nginx 재시작 후 정상 복구되었다.
- 이번 슬라이스는 Phase7-pre 인프라 작업을 포함하지 않는다.
- Redis HA, Object Storage, TLS, Kubernetes 전환은 이번 슬라이스의 구현 범위 밖이다.

### 목표

- app rebuild/recreate/scale 이후 role routing이 올바른지 자동 검증하는 synthetic check를 추가한다.
- `/api/`, `/api/ws/`, `/api/admin/`가 각각 API, WebSocket, Admin 실행 모듈로 라우팅되는지 확인한다.
- check 결과는 사람이 읽을 수 있는 JSON summary와 프로세스 exit code로 제공한다.
- 하나라도 실패하면 exit code `1`로 종료해 배포 스크립트나 smoke test에서 release gate로 사용할 수 있게 한다.
- Compose 운영 기간의 대응 절차는 "app recreate/scale 이후 Nginx recreate 또는 restart, 이후 synthetic check 실행"으로 명문화한다.
- Nginx 동적 DNS resolver 기반 재설계는 이번 슬라이스에서 기본안으로 채택하지 않는다.

## 2. 해결 접근

### 선택한 접근

`Synthetic check + 배포 후 Nginx recreate/restart 절차 명문화`를 기본안으로 채택한다.

이 접근은 Compose 기간 한정 문제에 과투자하지 않으면서, 실제로 운영자가 확인해야 하는 release gate를 자동화한다. Kubernetes 전환 후에는 Service/Ingress가 안정 endpoint를 제공하므로 이 check는 회귀 방지용으로 축소될 수 있다.

### 대안 비교

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| Synthetic check + Nginx restart 절차 | 구현이 단순하고 Compose 운영 절차에 바로 붙일 수 있다 | Nginx가 자동으로 DNS를 재해석하는 구조는 아니다 | 기본안 |
| Nginx resolver + variable proxy_pass | 컨테이너 IP 변경 후 DNS 재조회 가능성이 높다 | upstream load balancing, WebSocket proxy, Docker DNS 동작 검증 비용이 커진다 | 운영 부담이 반복될 때 별도 슬라이스로 재평가 |
| Synthetic check만 추가 | 변경 범위가 가장 작다 | 실패 시 표준 대응 절차가 없어 Phase 7 요구를 덜 만족한다 | 부족 |

## 3. 설계

### 3.1 파일 구조

- `scripts/lib/phase7RoleRoutingCheckPlan.mjs`
  - CLI 인자 파싱, route check plan 생성, check 결과 요약, exit code 판정 로직을 담당한다.
  - 순수 함수 중심으로 작성해 Node test에서 실제 네트워크 없이 검증한다.
- `scripts/phase7-role-routing-check.mjs`
  - 실제 HTTP 요청을 수행하는 실행 스크립트다.
  - `phase7RoleRoutingCheckPlan.mjs`의 plan을 받아 route별 요청을 실행하고 JSON summary를 출력한다.
- `scripts/lib/phase7RoleRoutingCheckPlan.test.mjs`
  - 인자 파싱, route plan 생성, response 판정, summary 판정 테스트를 담당한다.
- `docs/phase7_nginx_role_routing_check.md`
  - 실행 절차, 배포 후 Nginx restart/recreate 절차, 실패 시 해석 방법을 기록한다.

### 3.2 Synthetic Check 대상

기본 route는 다음 네 가지다. `actuator/health`는 모든 Spring role에서 열릴 수 있으므로 role-specific 판정에는 사용하지 않는다.

| 이름 | 메서드 | 경로 | 기대 판정 |
| --- | --- | --- | --- |
| `nginx-health` | `GET` | `/health` | Nginx 자체 health가 `200`을 반환한다 |
| `api-ws-ticket-auth-required` | `POST` | `/api/ws-tickets` | API role의 ticket controller가 인증 실패 `400` JSON을 반환한다 |
| `admin-health` | `GET` | `/api/admin/health` | Admin role health가 `2xx`를 반환한다 |
| `websocket-invalid-ticket-handshake` | `GET` + `Upgrade: websocket` | `/api/ws/chat?ticket=phase7-invalid-routing-ticket` | WebSocket role의 handshake interceptor가 invalid ticket을 `401`로 거부한다 |

API check는 실제 사용자를 만들지 않기 위해 인증 header 없이 `/api/ws-tickets`를 호출한다. 올바른 API role이면 `AuthenticatedUserResolver`와 `GlobalExceptionHandler`를 거쳐 `400` JSON이 반환된다. 이 경로가 WebSocket/Admin role로 오라우팅되면 일반적으로 `404`가 나오므로 stale upstream을 감지할 수 있다.

WebSocket check는 일반 HTTP 요청이 아니라 raw WebSocket handshake 형식으로 보낸다. 올바른 WebSocket role이면 invalid one-time ticket을 consume하지 못해 `401`로 거부된다. API/Admin role로 오라우팅되면 `/api/ws/chat` mapping이 없으므로 `404` 또는 handshake와 무관한 응답이 나와 실패로 분류한다.

### 3.3 CLI 계약

기본 실행:

```bash
node scripts/phase7-role-routing-check.mjs
```

옵션:

```text
--base-url http://localhost
--admin-token test
--timeout-ms 3000
--json
```

환경 변수:

```text
CHAT_PHASE7_BASE_URL
CHAT_ADMIN_TOKEN
CHAT_PHASE7_ROUTE_TIMEOUT_MS
```

CLI 인자가 환경 변수보다 우선한다. 둘 다 없으면 `baseUrl=http://localhost`, `adminToken=test`, `timeoutMs=3000`을 기본값으로 사용한다.

### 3.4 JSON Summary

성공 예시:

```json
{
  "ok": true,
  "baseUrl": "http://localhost",
  "checks": [
    {
      "name": "api-ws-ticket-auth-required",
      "method": "POST",
      "url": "http://localhost/api/ws-tickets",
      "ok": true,
      "status": 400
    }
  ],
  "failed": []
}
```

실패 예시:

```json
{
  "ok": false,
  "baseUrl": "http://localhost",
  "checks": [
    {
      "name": "api-ws-ticket-auth-required",
      "method": "POST",
      "url": "http://localhost/api/ws-tickets",
      "ok": false,
      "status": 404,
      "reason": "unexpected_status"
    }
  ],
  "failed": ["api-ws-ticket-auth-required"]
}
```

### 3.5 대응 절차

Compose 배포, app rebuild, app recreate, worker scale 복구 후 다음 순서로 실행한다.

```bash
docker compose up -d --no-build nginx
node scripts/phase7-role-routing-check.mjs --base-url http://localhost --admin-token "$CHAT_ADMIN_TOKEN"
```

라우팅 check가 실패하면 Nginx를 recreate 또는 restart한 뒤 다시 실행한다.

```bash
docker compose up -d --force-recreate --no-build nginx
node scripts/phase7-role-routing-check.mjs --base-url http://localhost --admin-token "$CHAT_ADMIN_TOKEN"
```

두 번째 check도 실패하면 해당 배포는 release gate fail로 본다.

## 4. 테스트 전략

TDD 순서:

1. `parseRoleRoutingCheckArgs` 테스트를 먼저 작성한다.
2. 실패를 확인한다.
3. 인자 파싱을 구현한다.
4. `buildRoleRoutingChecks` 테스트를 작성하고 실패를 확인한다.
5. route plan 생성을 구현한다.
6. response 판정 테스트를 작성하고 실패를 확인한다.
7. status/body/header 판정 로직을 구현한다.
8. summary exit code 테스트를 작성하고 실패를 확인한다.
9. summary 생성 로직을 구현한다.
10. 실행 스크립트 문법 검증과 가능하면 실제 Compose check를 실행한다.

검증 명령:

```bash
node --test scripts/lib/phase7RoleRoutingCheckPlan.test.mjs
node --check scripts/phase7-role-routing-check.mjs
node scripts/phase7-role-routing-check.mjs --base-url http://localhost --admin-token "$CHAT_ADMIN_TOKEN"
```

마지막 명령은 Compose가 실행 중일 때만 수행한다. Compose가 실행 중이 아니면 단위 테스트와 문법 검증을 완료 조건으로 보고, 실제 synthetic check는 실행 불가 상태를 명확히 기록한다.

## 5. 복잡도

- check plan 생성 시간 복잡도: `O(R)`
- synthetic check 실행 시간 복잡도: `O(R)`
- summary 생성 시간 복잡도: `O(R)`
- 공간 복잡도: `O(R)`

여기서 `R`은 route check 개수다. 기본값은 네 개이므로 운영상 상수에 가깝다.

## 6. 주의사항

> - 이 슬라이스는 Docker Compose 운영 기간의 stale upstream 리스크를 다룬다. Kubernetes 전환 후에는 Service/Ingress가 안정 endpoint를 제공하므로 근본 원인이 사라질 수 있다.
> - WebSocket endpoint는 일반 HTTP가 아니라 raw Upgrade handshake로 검증한다. 올바른 WebSocket role에서 invalid ticket이 `401`로 거부되는 것을 정상으로 본다.
> - metric cardinality나 부하 테스트 자체는 이번 슬라이스의 범위가 아니다. 이번 작업은 Phase 7 release gate 중 "nginx stale upstream으로 인한 오라우팅 없음"을 검증하는 첫 조각이다.
> - Nginx resolver 기반 동적 upstream은 이번 기본안이 아니다. 반복적으로 restart 절차가 운영 부담이 되거나 Compose 운영 기간이 길어질 때 별도 슬라이스로 재평가한다.

## 7. 후속 질문

- 후속 Phase 7 슬라이스는 범위가 운영 기준, release gate, metric semantics, chaos/load test를 바꾸는 경우 설계 문서를 먼저 작성한 뒤 구현한다.
- `phase6-fanout-takeover-smoke.mjs`의 `finally` 복구 후 이 check를 자동 실행할 것인가, 아니면 독립 스크립트로만 유지할 것인가?
- 다음 Phase7 슬라이스를 fanout owner kill takeover summary 확장으로 둘 것인가, reconnect/load test metric으로 둘 것인가?
