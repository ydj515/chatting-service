# Production Hardening Tasks

이 문서는 Phase 2.5 이후 운영 공개 전 또는 운영 안정화 단계에서 분리했거나 완료한 hardening 항목을 정리한다.

---

## 1. WebSocket Ticket Rate Limit Lua Script 전환

### 상태

- 분류: Security / Reliability Hardening
- 적용 시점: Phase 2.5 완료 후 Production Hardening Gate
- 현재 구현 상태: 반영 완료
- 관련 문서: [ws_ticket_analysis.md](./ws_ticket_analysis.md)

### 문제

WebSocket one-time ticket 발급 API는 Redis rate limit counter를 사용한다. 단순 구현은 다음 두 명령을 순서대로 호출한다.

```text
INCR rate-limit-key
EXPIRE rate-limit-key window
```

이 구조에서 `INCR` 성공 후 `EXPIRE` 전에 애플리케이션 crash, Redis timeout, 네트워크 단절이 발생하면 TTL 없는 rate limit key가 남을 수 있다. 이 key가 계속 증가하거나 남아 있으면 특정 user/IP가 계속 rate limited 상태처럼 보일 수 있고, 사용자는 WebSocket ticket 발급 실패와 재연결 실패를 경험한다.

### 현재 완화책

초기 완화책은 `count == 1`일 때 TTL을 설정하고, 이후 요청에서 `TTL == -1`을 발견하면 같은 window로 TTL을 복구하는 방식이었다. 현재 구현은 이 경로를 Lua script로 승격해 Redis 내부에서 원자 처리한다.

### 목표 구현

Redis Lua script로 `INCR + PEXPIRE + TTL repair`를 Redis 내부에서 원자 처리한다.

예시:

```lua
local current = redis.call('INCR', KEYS[1])
local ttl = redis.call('PTTL', KEYS[1])

if current == 1 or ttl == -1 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end

if current <= tonumber(ARGV[2]) then
  return 1
end

return 0
```

`ARGV[1]`은 window milliseconds, `ARGV[2]`는 limit이다.

### Redis Cluster Hash Slot 판단

현재 Lua script는 Redis Cluster hash slot 문제를 피하도록 **단일 key script**로 유지한다.

- user rate limit script는 `chat:ws-ticket:rate:user:{userId}` 계열 key 1개만 `KEYS[1]`로 받는다.
- IP rate limit script는 `chat:ws-ticket:rate:ip:{hashedIp}` 계열 key 1개만 `KEYS[1]`로 받는다.
- user key와 IP key를 같은 Lua script에 함께 넘기지 않는다.
- Lua script 내부에서 `KEYS[1]` 외의 Redis key를 조합하거나 접근하지 않는다.

Redis Cluster에서 Lua script가 여러 key를 받으면 모든 key가 같은 hash slot에 있어야 한다. 현재 구현은 script 호출 1회당 key가 1개뿐이므로 cross-slot 문제가 발생하지 않는다. 따라서 현 단계에서는 rate limit key에 hash tag를 강제로 붙일 필요가 없다.

향후 user rate limit과 IP rate limit을 하나의 script에서 완전 원자 처리하려면 두 key가 같은 hash slot에 있어야 한다. 다만 모든 key를 `{ws-ticket}` 같은 공통 hash tag로 묶으면 한 slot에 트래픽이 몰릴 수 있으므로 기본 방향으로 두지 않는다. 그 경우에는 다음 중 하나를 별도 설계로 선택한다.

- user/IP를 계속 단일 key script로 분리하고, 두 제한의 완전 원자성은 포기한다.
- 같은 hash slot이 필요한 범위만 hash tag로 묶되 slot skew와 hot key 위험을 별도 검증한다.
- Lua script 대신 RedisGears, 별도 rate limit service, 또는 edge/WAF rate limit과 조합한다.

### 완료 기준

- user 기준 ticket issue rate limit이 Lua script 경로를 사용한다.
- IP 기준 ticket issue rate limit이 Lua script 경로를 사용한다.
- Lua script 실행 실패 시 ticket 발급은 fail-closed로 실패한다.
- `INCR` 이후 TTL이 없는 key가 남는 장애 모드가 단위 테스트로 방어된다.
- Redis Cluster 전환 시 단일 key script라 hash tag 없이 cross-slot 문제가 없다는 판단이 문서화되어 있다.
- ticket issue success/failure/rate-limited count가 metric으로 관측된다.
- ticket issue latency가 `chat.websocket.ticket.issue.latency` timer로 관측된다.
- Redis Lua script 실패가 `chat.websocket.ticket.rate_limit.script.failures` counter로 관측된다.
- Lua script failure metric은 `scope=user|ip` tag를 가진다.
- Phase 7에서는 위 metric을 dashboard와 alert rule에 연결한다.

### 복잡도

- 시간 복잡도: `O(1)`
- 공간 복잡도: `O(1)`

### 주의사항

> - Redis Cluster에서 Lua script는 같은 hash slot의 key만 한 번에 다룰 수 있다. user key와 IP key를 하나의 script에서 동시에 처리하지 않는 편이 단순하다.
> - 현재 rate limit script는 호출 1회당 key 1개만 사용하므로 hash tag가 필요 없다. 여러 key를 한 script로 합치면 Redis Cluster hash slot 설계를 다시 해야 한다.
> - Lua script 장애 시 fail-open으로 ticket을 발급하면 abuse 방어가 깨질 수 있다. 운영 기본값은 fail-closed가 맞다.
> - 이전 TTL repair 방식은 임시 완화책이며, 현재 최종 구현은 Lua script 원자 처리다.

### 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 현재 TTL repair 유지 | 구현이 단순하고 장애 모드를 일부 완화한다 | `INCR + EXPIRE` 사이 중간 상태 자체는 남는다 | 더 이상 기본안으로 두지 않음 |
| Lua script 전환 | 원자성, round-trip 감소, 장애 모드 축소 | script 관리와 Redis Cluster 제약 검토 필요 | 적용 완료 |
| Redis transaction 사용 | 명령 묶음 의도가 명확하다 | Redis transaction은 중간 로직과 조건 처리에 Lua보다 불편하다 | 우선순위 낮음 |

---

## 2. Docker Compose Nginx Upstream DNS Stale 대응

### 상태

- 분류: Infra / Deployment Hardening
- 적용 시점: 별도 Infra Hardening task
- 현재 구현 상태: `mise run start`, `mise run start:all`, `start-cluster.sh`에 nginx restart 반영 완료
- 관련 문서: [infrastructure.md](./infrastructure.md)

### 문제

Docker Compose에서 app 컨테이너를 재생성하면 컨테이너 IP가 바뀔 수 있다. Nginx는 일반적인 `upstream` 설정에서 hostname을 시작 시점에 resolve하고, 재기동 전까지 이전 IP를 계속 사용할 수 있다.

이전 IP가 다른 role의 컨테이너에 재사용되면 다음과 같은 일이 발생할 수 있다.

1. `chat-api-app-*` 컨테이너가 재생성된다.
2. 기존 API 컨테이너 IP가 `chat-websocket-app-*` 같은 다른 컨테이너에 재사용된다.
3. nginx는 예전 API upstream IP를 계속 사용한다.
4. `/api/users/register` 요청이 WebSocket 앱으로 전달된다.
5. WebSocket 앱에는 REST user controller가 없으므로 `404`가 발생한다.

### 영향

- 로컬 Docker Compose 또는 Compose 기반 스테이징에서 재현 가능하다.
- Kubernetes 환경에서는 Service/Ingress가 stable virtual endpoint를 제공하므로 같은 형태의 문제가 줄어든다.
- VM + Docker Compose 운영을 선택한다면 배포 절차에서 반드시 다뤄야 한다.

### 현재 runbook

app 컨테이너 재생성 후 nginx를 재시작해 upstream DNS를 다시 resolve한다.

```bash
mise run start
mise run verify:chat
```

수동으로 app 컨테이너만 재생성한 경우에는 다음 명령을 실행한다.

```bash
mise run restart:nginx
mise run verify:chat
```

### 목표 구현

현재는 첫 번째 방식을 적용했다. 필요해지면 두 번째 또는 세 번째 방식으로 확장한다.

1. Compose 배포 스크립트에 app 재생성 후 `docker compose restart nginx`를 명시적으로 포함한다.
2. nginx에 Docker DNS resolver(`127.0.0.11`)와 동적 `proxy_pass` 전략을 적용한다.
3. 운영 배포는 Kubernetes Service/Ingress 기반으로 전환하고 Compose는 local/dev 전용으로 제한한다.

### 완료 기준

- app 컨테이너를 재생성한 뒤에도 `/api/`, `/api/ws/`, `/api/admin/` 요청이 올바른 role로 라우팅된다.
- `mise run verify:chat`가 app rebuild 직후 안정적으로 통과한다.
- nginx access log의 `upstream` 주소가 현재 container IP와 일치한다.
- 배포 runbook 또는 mise task에 nginx stale DNS 대응 절차와 health wait가 포함되어 있다.

### 복잡도

- 요청 처리 시간 복잡도: `O(1)`
- 추가 공간 복잡도: `O(1)`

### 주의사항

> - `docker compose up -d --build`만으로는 nginx가 기존 upstream IP를 갱신하지 않을 수 있다.
> - nginx `resolver` 기반 동적 proxy는 설정이 복잡해질 수 있으므로 local/dev와 운영 배포 방식을 분리해서 판단해야 한다.
> - 이 문제는 애플리케이션 controller mapping 문제가 아니라 배포/라우팅 계층의 stale endpoint 문제다.

### 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| app 재생성 후 nginx restart | 단순하고 즉시 효과가 있다 | nginx 연결이 재시작된다 | Compose local/staging에 적합 |
| nginx dynamic DNS 설정 | nginx 재시작 빈도를 줄일 수 있다 | 설정 복잡도와 검증 비용이 늘어난다 | 필요 시 적용 |
| Kubernetes Service/Ingress | 운영 표준에 가깝고 endpoint 추상화가 강하다 | 클러스터 운영 비용이 있다 | 최종 운영 환경 권장 |
