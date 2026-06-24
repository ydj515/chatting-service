# Phase 7 WebSocket Reconnect Chaos Orchestration

이 문서는 Phase 7에서 gateway 장애(rolling restart / hard kill)를 reconnect storm과 시간적으로 겹쳐 주입하고, 장애 구간 reconnect gate와 recovery SLO gate를 release-blocking으로 판정하는 orchestrator의 실행 절차와 기준을 정의한다.

기존 자산을 연결한다. `scripts/phase7-reconnect-load.mjs`(synthetic reconnect storm)를 child로 돌리면서 gateway에 장애를 주입한다. 단일 컨테이너 복구만 보는 [chaos runbook](./phase7_chaos_test_runbook.md)과 달리, 이 슬라이스는 장애 **구간에 storm gate가 유지되는지**를 본다. reconnect gate 기준은 [reconnect load test 시나리오](./phase7_reconnect_load_test_scenarios.md) 4.2 / 4.3을 그대로 따른다.

## 0. 실행 가능한 Orchestrator

기본은 dry-run이며 실제 주입과 storm은 `--execute`에서만 일어난다.

```bash
# dry-run: 실제 docker 명령/ storm 없이 fault 계획과 reconnect 인자만 출력한다.
node scripts/phase7-reconnect-chaos.mjs --fault gateway-rolling-restart

# 실제 주입 + reconnect storm + release gate
node scripts/phase7-reconnect-chaos.mjs --fault gateway-rolling-restart --execute

# 실제 주입 + reconnect storm + 12초 recovery SLO gate
node scripts/phase7-reconnect-chaos.mjs --fault gateway-rolling-restart --execute \
  --max-recovery-slo-ms 12000

# gateway 1개 즉시 kill 중 reconnect burst (나머지 replica가 서빙)
node scripts/phase7-reconnect-chaos.mjs --fault gateway-hard-kill --execute
```

환경 변수(reconnect-load child로 전달):

```text
CHAT_HTTP_URL=http://localhost/api
CHAT_WS_URL=ws://localhost/api/ws/chat
CHAT_PHASE7_RECONNECT_TIMEOUT_MS=15000
CHAT_PHASE7_CHAOS_DOCKER_TIMEOUT_MS=30000
CHAT_PHASE7_RECONNECT_CHAOS_READY_POLL_MS=250
```

주요 옵션:

| 옵션 | 기본값 | 의미 |
| --- | --- | --- |
| `--fault` | (필수) | `gateway-rolling-restart` 또는 `gateway-hard-kill` |
| `--gateways` | fault 모드 기본 | 주입 대상 gateway 서비스 csv 오버라이드 |
| `--execute` | (off) | 실제 docker 주입 + storm 실행. 미지정 시 dry-run |
| `--no-restore` | (off) | hard-kill 시 컨테이너 자동 복구(`docker start`)를 건너뜀 |
| `--rolling-step-delay-ms` | `2000` | rolling-restart에서 step 사이 지연(동시 다운 방지) |
| `--inject-after-ms` | `0` | storm ready 신호 후 주입까지 추가 지연 |
| `--ready-timeout-ms` | `60000` | storm ready 신호 대기 상한 |
| `--max-recovery-slo-ms` | `30000` | 첫 fault 주입부터 reconnect storm 완료까지의 wall-clock SLO |
| `--clients` | `5` | reconnect storm client 수 |
| `--reconnects-per-client` | `6` | client당 reconnect 시도 수 |
| `--attempt-spacing-ms` | `1000` | reconnect 시도 간격 |
| `--jitter-ms` | `250` | reconnect 시도 jitter |
| `--cohort` | `synthetic` | `direct`, `nat_proxy`, `mobile_carrier`, `synthetic` 중 하나 |
| `--reason` | fault 모드 기본 | `network_flap`, `gateway_restart`, `gateway_kill`, `deploy`, `unknown` 중 하나 |
| `--scenario` | fault 모드 라벨 | reconnect summary scenario 라벨 |
| `--min-ticket-issue-success-ratio` 등 gate 4종 | 미지정 시 child 기본 | reconnect release gate 임계값 오버라이드 |
| `--json` | (off) | summary를 한 줄 JSON으로 출력 |

> `--execute`는 실제 gateway를 restart/kill한다. 운영·공유 환경에서는 금지하고 Compose 로컬·CI에서만 사용한다.

## 1. Fault 카탈로그

| Fault 모드 | 기본 대상 | 주입 | 복구 | 검증 의미 |
| --- | --- | --- | --- | --- |
| `gateway-rolling-restart` | `chat-websocket-app-1`, `chat-websocket-app-2` | 각 replica를 `--rolling-step-delay-ms` 간격으로 순차 `docker restart` | restart가 컨테이너를 되살림(별도 복구 없음) | 배포/재시작 중 reconnect storm이 gate를 유지한다 |
| `gateway-hard-kill` | `chat-websocket-app-1` | `docker kill` | `--no-restore`가 아니면 `docker start`로 복구 | 1개 replica 즉시 장애 시 나머지가 서빙하며 storm이 gate를 유지한다 |

gateway는 Compose에서 2 replica이고 `container_name`/`hostname`이 고정이라 `docker compose ps -q <service>`로 id를 찾아 service 단위로 주입한다. `--gateways`로 대상을 바꿀 수 있다(예: 2개 모두 hard-kill).

## 2. 주입 타이밍 (ready-file 신호)

storm과 장애가 겹치지 않으면 chaos 의미가 사라진다. orchestrator는 [takeover smoke](./phase7_fanout_takeover_summary.md)의 metadata-file 패턴을 따른다.

1. orchestrator가 reconnect-load child를 `--ready-file <temp>`와 함께 spawn한다.
2. child는 모든 client 등록을 마치고 attempt phase 직전에 ready-file(`{ startedAt, clients }`)을 쓴다.
3. orchestrator가 ready-file을 감지하면 `--inject-after-ms` 지연 후 fault step을 실행한다.
4. 신호→첫 주입까지의 offset을 summary `injectionOffsetMs`에 기록한다.

기본 storm 길이 ≈ `reconnectsPerClient * attemptSpacingMs + jitter` ≈ 6초로, rolling-restart 2 step과 hard-kill 복구 구간을 덮는다. storm을 더 길게 하려면 `--reconnects-per-client`/`--attempt-spacing-ms`를 키운다.

## 3. 복구 판정 (release-blocking 기준)

release gate는 장애 구간 storm의 reconnect gate와 recovery SLO gate를 함께 쓴다.

| 항목 | 기준 |
| --- | --- |
| ticket 발급 성공률 | reconnect-load `--min-ticket-issue-success-ratio` (기본 `0.999`) 이상 |
| handshake 성공률 | reconnect-load `--min-handshake-success-ratio` (기본 `0.999`) 이상 |
| rate-limit 실패율 | reconnect-load `--max-rate-limit-failure-ratio` (기본 `0.001`) 이하 |
| cohort 실패율 | reconnect-load `--max-cohort-failure-ratio` (기본 `0.003`) 이하 |
| recovery SLO | 첫 fault 주입부터 reconnect-load child 완료까지 `--max-recovery-slo-ms` 이하 |

release-blocking 판정:

- 장애 구간 storm gate가 하나라도 깨지면 release-blocking.
- storm gate가 통과해도 `recoveryElapsedMs > maxRecoverySloMs`이면 release-blocking.
- SLO 초과 시 top-level `failedGates`에 `recovery_slo_ms`를 추가한다.
- dry-run은 항상 비blocking이다.

exit code: `0` = 비blocking, `1` = release-blocking, `2` = 사용법/인자 오류.

### Summary 예시

```json
{
  "faultMode": "gateway-rolling-restart",
  "dryRun": false,
  "injectedContainers": ["chat-websocket-app-1", "chat-websocket-app-2"],
  "injectionOffsetMs": 120,
  "recoveryElapsedMs": 8200,
  "maxRecoverySloMs": 30000,
  "recoverySloMet": true,
  "reconnect": {
    "ok": true,
    "scenario": "gateway-rolling-restart",
    "durationSeconds": 7,
    "rates": { "ticketIssueSuccessRate": 1, "rateLimitFailureRate": 0, "handshakeSuccessRate": 1 },
    "failedGates": []
  },
  "failedGates": [],
  "releaseBlocking": false
}
```

dry-run은 `reconnect: null`, `injectionOffsetMs: null`, `recoveryElapsedMs: null`, `recoverySloMet: true`이며 `plan`(fault step + reconnect 인자)을 추가로 출력한다.

## 4. 수동 절차 (orchestrator 없이)

1. baseline 확인: `curl -f http://localhost/api/actuator/health`가 `UP`인지 본다.
2. reconnect storm 시작: `node scripts/phase7-reconnect-load.mjs --scenario gateway-rolling-restart --clients 5 --reconnects-per-client 6`.
3. storm이 도는 동안 gateway 주입:
   - rolling restart: `docker restart chat-websocket-app-1`, 잠시 후 `docker restart chat-websocket-app-2`.
   - hard kill: `docker kill chat-websocket-app-1` 후 `docker start chat-websocket-app-1`.
4. 첫 장애 주입부터 storm 종료까지의 wall-clock과 storm summary의 `ok`/`failedGates`로 release 여부를 기록한다.

## 5. Rollback / 복구

- hard-kill `--no-restore`로 주입한 경우 수동 복구: `docker start <id>` 또는 `docker compose up -d chat-websocket-app-1`.
- rolling-restart는 `docker restart`가 컨테이너를 되살리므로 추가 복구가 필요 없다.
- 주입 후에도 health가 `UP`으로 돌아오지 않으면 release를 막고 nginx upstream과 gateway 로그를 조사한다.

## 6. 복잡도

- fault step 실행 시간 복잡도: `O(G)` (`G`는 대상 gateway 수)
- summary 병합 시간 복잡도: `O(F)` (reconnect 집계 `O(N)`은 child 내부, `N`은 attempt 수)
- 공간 복잡도: `O(G + C + F)` (`C`는 cohort 수, `F`는 failed gate 수)

## 7. 주의사항

> - `--execute`는 실제 gateway를 restart/kill하므로 운영·공유 환경에서 금지한다. Compose 로컬·CI 한정.
> - storm 길이가 fault window보다 길어야 의미가 있다. ready-file 신호 후에만 주입하고 storm 인자를 충분히 키운다.
> - `recoveryElapsedMs`는 gateway health polling 시간이 아니라 첫 fault 주입부터 reconnect-load child 종료까지의 시간이다.
> - 기본 `30000ms` SLO는 conservative baseline이다. staging/CI 관측 후 fault mode별로 좁힌다.
> - `--rolling-step-delay-ms`를 `0`으로 두면 전체 gateway가 동시에 내려가 storm이 모두 실패할 수 있다.
> - hard-kill `--no-restore` 사용 시 컨테이너 수동 복구가 필요하다.
> - synthetic 계정/room만 사용하고, summary나 로그에 session token, ticket, raw IP, message body 같은 민감값을 넣지 않는다.

## 8. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 신규 orchestrator(reconnect-load child + gateway fault) | 기존 두 자산 재사용, gate 일관 | 파일 1세트 추가 | 선택 |
| 기존 chaos runner에 storm check 추가 | 파일 수 최소 | functional probe와 storm gate 의미가 섞임 | 제외 |
| reconnect-load에 docker 주입 내장 | runner 1개 | 순수 storm runner가 docker에 결합됨 | 제외 |
| 고정 offset 주입(ready-file 없이) | reconnect-load 무수정 | 등록 시간 편차로 겹치는 시점이 부정확 | 제외 |
| health polling recovery SLO 추가 | gateway 복구 상태를 직접 검증 | reconnect storm gate와 범위가 커짐 | 후속 |

## 9. 후속 질문

- fault mode별 recovery SLO를 staging baseline에 맞춰 분리할 것인가?
- replica 수가 늘면 hard-kill 대상을 비율(50%) 기반으로 바꿀 것인가?
- 이 orchestrator를 chaos runner와 함께 CI nightly release gate에 연결할 것인가?
