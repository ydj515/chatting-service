# Phase 7 WebSocket Reconnect Chaos Orchestration 설계

이 문서는 Phase 7 reconnect chaos orchestration 슬라이스(후보 B)의 범위, fault 모델, 주입 타이밍, release-blocking gate를 구현 전에 고정한다. gateway rolling restart / hard kill을 reconnect storm runner와 한 orchestrator로 연결하는 것이 목표다.

## 1. 문제 이해 / 요구사항 정리

### 조건

- gateway는 Compose에서 2 replica(`chat-websocket-app-1`, `chat-websocket-app-2`)이고, 둘 다 `container_name`/`hostname`이 고정이라 `docker compose ps -q <service>` → `docker restart`/`docker kill`/`docker start` 단위로 다룬다(worker처럼 `--scale` 대상이 아니다).
- 기존 `scripts/phase7-chaos-runner.mjs`는 단일 컨테이너 장애를 주입한 뒤 health/functional/lag **복구만** 폴링한다. 장애 구간에 reconnect storm을 함께 돌리지 않는다.
- 기존 `scripts/phase7-reconnect-load.mjs`는 synthetic reconnect storm을 생성하고 ticket/handshake/cohort gate로 `ok`/`failedGates` summary를 내지만, **장애를 주입하지 않는다**. 구조상 모든 client를 먼저 등록한 뒤 attempt를 동시에 실행한다.
- `scripts/phase6-fanout-takeover-smoke.mjs`는 load child를 spawn하고 `--metadata-file` 신호로 타이밍을 잡아 `docker kill` 후 summary를 모으고 복구하는 orchestration 선례를 가진다.
- reconnect 시나리오 문서 4.2(Gateway Rolling Restart) / 4.3(Gateway Hard Kill)는 pass 기준을 정의했지만 자동 실행 runner가 없다.
- 순수 로직은 `scripts/lib/*.mjs`(+`.test.mjs`), docker/HTTP I/O는 얇은 `scripts/*.mjs` runner에 두는 기존 패턴을 따른다.

### 목표

- Compose 환경에서 `gateway-rolling-restart`, `gateway-hard-kill` 두 fault 모드를 reconnect storm과 **시간적으로 겹쳐** 자동 실행한다.
- 장애 구간 storm의 reconnect gate(ticket 발급/handshake 성공률, rate-limit·cohort 실패율)를 그대로 release-blocking 판정에 쓴다.
- 기본은 dry-run, `--execute`일 때만 실제 주입한다(chaos runner와 동일 안전장치).
- summary에 fault 메타데이터(mode, 주입 컨테이너, 주입 offset)와 reconnect 결과(`ok`/`failedGates`)를 함께 남긴다.

### 비범위

- Kubernetes pod 장애 orchestration (후속).
- 네트워크 partition/latency 주입(tc/netem), CPU/메모리 압박 (후속 슬라이스).
- 신규 서버 metric 추가 — reconnect 슬라이스의 TODO metric 영역은 건드리지 않는다.
- 실제 IP/NAT/proxy 재현 — cohort는 summary 분류용 label로만 유지한다.
- 별도 복구 시간(SLO) gate — 이번 슬라이스 gate는 reconnect 비율로 한정한다(후속 질문으로 남김).

## 2. 접근안

### 산출물 파일

| 파일 | 역할 |
| --- | --- |
| `scripts/lib/phase7ReconnectChaosPlan.mjs` | 순수 로직: 인자 파싱, gateway fault step 빌드, reconnect-load 인자 빌드, summary 병합, exit code |
| `scripts/lib/phase7ReconnectChaosPlan.test.mjs` | 단위 테스트 (TDD 대상) |
| `scripts/phase7-reconnect-chaos.mjs` | 얇은 runner: gateway 컨테이너 resolve, reconnect-load child spawn, ready-file 타이밍 주입, 복구 |
| `scripts/lib/phase7ReconnectLoadPlan.mjs` | `--ready-file` 옵션 파싱 추가, cohort/reason enum export |
| `scripts/phase7-reconnect-load.mjs` | 등록 완료 후 storm 시작 시점에 ready-file 기록 |
| `docs/phase7_reconnect_chaos_orchestration.md` | 운영 runbook |
| `docs/phase7_slices.md` | 인덱스: 후보 B를 현재 슬라이스(9)로 이동 |

### Fault 모델

| 모드 | 기본 대상 | 주입 | 복구 | 검증 의미 |
| --- | --- | --- | --- | --- |
| `gateway-rolling-restart` | `chat-websocket-app-1`, `chat-websocket-app-2` | 각 replica를 inter-step 지연을 두고 순차 `docker restart` | restart가 컨테이너를 되살리므로 별도 복구 불필요 | 배포/재시작 중 reconnect storm이 gate를 유지한다 |
| `gateway-hard-kill` | `chat-websocket-app-1` | `docker kill` | `--no-restore`가 아니면 `docker start`로 복구 | 1개 replica 즉시 장애 시 나머지가 서빙하며 storm이 gate를 유지한다 |

- 대상은 `--gateways`로 오버라이드한다(csv). 미지정 시 모드별 기본값을 쓴다.
- rolling-restart는 step 사이에 `--rolling-step-delay-ms`(기본 2000)를 둬서 동시 전체 다운을 피한다.
- hard-kill restore는 컨테이너가 정지 상태로 남으므로 `docker start <id>`로 되살린다(컨테이너명이 고정이라 takeover처럼 `--scale`이 필요 없다).

### 주입 타이밍 (ready-file 신호)

storm과 장애가 겹치지 않으면 chaos 의미가 사라지므로 타이밍 신뢰성이 핵심이다. takeover smoke의 `--metadata-file` 패턴을 따른다.

- `phase7-reconnect-load.mjs`에 `--ready-file <path>` 옵션을 추가한다. runner는 모든 client 등록을 마치고 attempt phase를 시작하기 직전에 `{ startedAt, clients }` JSON을 ready-file에 쓴다.
- orchestrator는 child를 spawn한 뒤 ready-file을 폴링(`--ready-timeout-ms`, 기본 60000)한다. 신호를 받으면 `--inject-after-ms`(기본 0) 추가 지연 후 fault step을 실행한다.
- 주입 offset(신호 시점→첫 주입까지 ms)을 summary에 기록한다.

### Reconnect-load 연동

orchestrator는 chaos에 적합한 기본값으로 reconnect-load child 인자를 구성한다. storm이 fault window보다 길어야 하므로 등록·재시도 횟수를 기본보다 키운다.

| orchestrator 옵션 | 기본값 | reconnect-load로 전달 |
| --- | --- | --- |
| `--clients` | `5` | `--clients` |
| `--reconnects-per-client` | `6` | `--reconnects-per-client` |
| `--attempt-spacing-ms` | `1000` | `--attempt-spacing-ms` |
| `--jitter-ms` | `250` | `--jitter-ms` |
| `--cohort` | `synthetic` | `--cohort` |
| `--reason` | 모드 기본(rolling→`gateway_restart`, kill→`gateway_kill`) | `--reason` |
| `--scenario` | 모드 라벨(`gateway-rolling-restart`/`gateway-hard-kill`) | `--scenario` |
| gate 비율 4종 | 미지정 시 child 기본값 | 지정 시에만 전달 |

기본 storm 길이 ≈ `reconnectsPerClient * attemptSpacingMs + jitter` ≈ 6초로, rolling-restart 2 step(약 2~4초) 및 hard-kill 복구 구간을 덮는다. cohort/reason은 reconnect-load의 bounded enum을 재사용해 orchestrator 단계에서 검증한다.

### Release gate

`releaseBlocking = !reconnectSummary.ok` — 장애 구간 storm의 reconnect gate가 하나라도 깨지면 release-blocking이다. dry-run은 항상 non-blocking. reconnect gate 기준(99.9% ticket 발급/handshake, rate-limit ≤ 0.1%, cohort ≤ 0.3%)은 reconnect 슬라이스 정의를 그대로 따른다.

### Summary JSON / exit code

```json
{
  "faultMode": "gateway-rolling-restart",
  "dryRun": false,
  "injectedContainers": ["chat-websocket-app-1", "chat-websocket-app-2"],
  "injectionOffsetMs": 120,
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

exit code: `0` = 비blocking, `1` = release-blocking, `2` = 사용법/인자 오류. dry-run은 항상 `0`. dry-run summary는 `reconnect: null`, `injectionOffsetMs: null`, `plan`(fault step + reconnect args)을 추가로 출력한다.

## 3. 복잡도

- fault step 실행 시간 복잡도: `O(G)` (`G`는 대상 gateway 수). rolling 지연은 `O(G)` 회 sleep.
- summary 병합 시간 복잡도: `O(1)` (reconnect 집계 `O(N)`은 child 내부, `N`은 attempt 수).
- 공간 복잡도: `O(G + C)` (`C`는 cohort 수).

## 4. 주의사항

> - `--execute`는 실제 gateway를 restart/kill하므로 운영·공유 환경에서 금지하고 Compose 로컬·CI에서만 사용한다.
> - storm과 장애가 겹치지 않으면 chaos 의미가 사라진다. ready-file 신호 후에만 주입하고, storm 길이가 fault window보다 길도록 기본값을 잡는다.
> - hard-kill `--no-restore` 사용 시 컨테이너 수동 복구(`docker start <id>`)가 필요하므로 runbook에 명시한다.
> - rolling-restart에서 step 지연을 0으로 두면 전체 gateway가 동시에 내려가 storm이 모두 실패할 수 있다. 기본 지연을 양수로 유지한다.
> - summary나 로그에 session token, ticket, raw IP, message body 같은 민감값을 넣지 않는다(reconnect-load의 redact를 유지한다).

## 5. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 신규 orchestrator(reconnect-load child + gateway fault) | 기존 두 자산을 재사용하고 gate가 reconnect 슬라이스와 일관된다 | 파일 1세트 추가 | 선택 |
| 기존 chaos runner에 storm check를 추가 | 파일 수 최소 | functional probe와 storm gate 의미가 섞여 판정이 흐려진다 | 제외 |
| reconnect-load에 docker 주입을 내장 | runner 1개로 끝 | 순수 storm runner가 docker에 결합되어 단위 테스트가 어려워진다 | 제외 |
| 고정 offset 주입(ready-file 없이) | reconnect-load 무수정 | 등록 시간 편차로 storm과 겹치는 시점이 부정확 | 제외(타이밍 신뢰성 우선) |

## 6. 후속 질문

- 주입→storm 완료 wall-clock에 대한 복구 SLO gate를 다음 슬라이스로 추가할 것인가?
- gateway hard-kill의 기본 대상을 1개에서 50%(2 replica 환경에서 1개)로 유지하되, replica 수가 늘면 비율 기반으로 바꿀 것인가?
- 이 orchestrator를 CI nightly release gate에 chaos runner와 함께 연결할 것인가?
