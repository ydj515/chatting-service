# Phase 7 Nginx Role Routing Synthetic Check

이 문서는 Docker Compose 환경에서 app rebuild/recreate/scale 이후 Nginx stale upstream으로 인한 role 오라우팅을 검증하는 방법을 정리한다.

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 7 본체 첫 슬라이스다.
- Phase7-pre 인프라 작업은 포함하지 않는다.
- Nginx는 Compose service name을 upstream으로 사용한다.
- app container IP가 바뀌면 Nginx가 stale upstream을 들고 있을 수 있다.
- stale upstream 상태에서는 `/api/`, `/api/ws/`, `/api/admin/` 요청이 잘못된 role로 라우팅될 수 있다.

### 목표

- role-specific endpoint를 호출해 Nginx 라우팅이 올바른지 확인한다.
- 실패 시 JSON summary와 non-zero exit code로 배포 절차에서 감지할 수 있게 한다.
- Compose 운영 기간에는 Nginx recreate/restart 후 check를 다시 실행하는 절차를 표준 대응으로 둔다.

## 2. 해결 접근

### 선택한 접근

`scripts/phase7-role-routing-check.mjs`는 다음 네 route를 확인한다.

| Check | 기대값 | 의미 |
| --- | --- | --- |
| `nginx-health` | `GET /health` -> `200` | Nginx 자체 health |
| `api-ws-ticket-auth-required` | `POST /api/ws-tickets` -> `400` | API role의 ticket controller와 인증 실패 처리 |
| `admin-health` | `GET /api/admin/health` -> `200` | Admin role의 health controller |
| `websocket-invalid-ticket-handshake` | `GET /api/ws/chat?ticket=phase7-invalid-routing-ticket` + `Upgrade: websocket` -> `401` | WebSocket role의 handshake interceptor |

`actuator/health`는 모든 Spring role에서 열릴 수 있으므로 role-specific 판정에는 사용하지 않는다.

## 3. 실행

기본 실행:

```bash
node scripts/phase7-role-routing-check.mjs
```

명시적 실행:

```bash
node scripts/phase7-role-routing-check.mjs \
  --base-url http://localhost \
  --admin-token "${CHAT_ADMIN_TOKEN:-test}" \
  --timeout-ms 3000 \
  --json
```

환경 변수:

```text
CHAT_PHASE7_BASE_URL
CHAT_ADMIN_TOKEN
CHAT_PHASE7_ROUTE_TIMEOUT_MS
```

CLI 인자가 환경 변수보다 우선한다.

## 4. 대응 절차

app recreate/scale 이후 먼저 synthetic check를 실행한다.

```bash
node scripts/phase7-role-routing-check.mjs --base-url http://localhost --admin-token "${CHAT_ADMIN_TOKEN:-test}"
```

실패하면 Nginx를 recreate하고 다시 실행한다.

```bash
docker compose up -d --force-recreate --no-build nginx
node scripts/phase7-role-routing-check.mjs --base-url http://localhost --admin-token "${CHAT_ADMIN_TOKEN:-test}"
```

두 번째 check도 실패하면 해당 배포는 release gate fail로 본다. 이때 JSON summary의 `failed` 배열과 각 check의 `reason`을 우선 확인한다.

## 5. 실패 해석

| Reason | 의미 | 우선 확인 |
| --- | --- | --- |
| `unexpected_status` | 기대 status와 다른 응답 | stale upstream, 잘못된 role routing, 대상 app 미기동 |
| `body_mismatch` | status는 맞지만 role-specific body가 다름 | 같은 status를 내는 다른 endpoint로 오라우팅됐는지 확인 |
| `request_failed` | 네트워크 요청 자체 실패 | Nginx 기동 여부, 포트, Compose network 상태 |

`api-ws-ticket-auth-required`에서 `404`가 나오면 API role 대신 WebSocket/Admin role로 라우팅됐을 가능성이 높다.

`websocket-invalid-ticket-handshake`에서 `404`가 나오면 WebSocket role 대신 API/Admin role로 라우팅됐을 가능성이 높다.

## 6. 복잡도

- route check 시간 복잡도: `O(R)`
- summary 생성 시간 복잡도: `O(R)`
- 공간 복잡도: `O(R)`

여기서 `R`은 route check 개수다. 기본값은 4개다.

## 7. 주의사항

> - 이 check는 Compose 운영 기간의 stale upstream release gate다. Kubernetes 전환 후에는 Service/Ingress가 안정 endpoint를 제공하므로 회귀 방지용으로 축소될 수 있다.
> - WebSocket check는 일반 HTTP 요청이 아니라 raw Upgrade handshake 형식으로 보낸다. 올바른 WebSocket role에서 invalid ticket이 `401`로 거부되는 것을 정상으로 본다.
> - `POST /api/ws-tickets`는 인증 header 없이 호출하므로 ticket을 실제로 발급하지 않는다.
> - `CHAT_ADMIN_TOKEN` 원문을 로그에 남기지 않는다. 현재 check summary에는 token 값을 출력하지 않는다.

## 8. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| Nginx recreate 후 check | 단순하고 Compose 기간에 충분하다 | Nginx가 자동으로 DNS를 재해석하는 구조는 아니다 | 기본 |
| Nginx resolver 기반 동적 upstream | restart 의존도를 줄일 수 있다 | Compose 한정 복잡도가 커지고 WebSocket proxy 검증 비용이 늘어난다 | 반복 장애 시 별도 슬라이스 |
| check만 실행하고 대응 절차 없음 | 구현 범위가 작다 | 실패 후 표준 복구 절차가 없어 release gate로 부족하다 | 사용하지 않음 |

## 9. 후속 질문

- `phase6-fanout-takeover-smoke.mjs` 복구 후 이 check를 자동 실행할 것인가?
- 다음 슬라이스를 fanout owner kill takeover summary 확장으로 둘 것인가?
