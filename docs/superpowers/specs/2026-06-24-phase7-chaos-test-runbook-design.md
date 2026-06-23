# Phase 7 Chaos Test Runbook 설계

이 문서는 Phase 7 chaos test runbook 슬라이스의 범위, 장애 주입 시나리오, 복구 판정 모델, release-blocking gate를 구현 전에 고정한다.

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 7은 운영 검증/계측/load·chaos test/release gate를 슬라이스 단위로 닫는 단계다.
- Compose 환경에는 `chat-websocket-app-*`(Gateway), `chat-worker-app-1`(Worker), `redis`, `postgres-replica` 등 장애 주입 대상이 있다.
- 기존 슬라이스(reconnect load test, takeover smoke)는 순수 로직은 `scripts/lib/*.mjs`(+`.test.mjs`)에, docker/HTTP I/O는 얇은 `scripts/*.mjs` runner에 두는 패턴을 쓴다.
- 장애 주입은 `docker compose ps -q <service>` + `docker kill`/`docker restart` 패턴으로 이미 takeover smoke에서 사용 중이다.
- chaos test는 장애 주입 범위와 release-blocking 판정 기준을 먼저 합의해야 의미가 흐려지지 않는다.

### 목표

- Compose 환경에서 4개 장애 시나리오(Gateway/Worker/Redis/replica)에 대한 운영 runbook 문서를 제공한다.
- 장애 주입과 복구 검증을 자동화하는 실행 runner를 제공한다. 기본은 dry-run, `--execute`일 때만 실제 주입한다.
- 복구 판정은 각 체크별로 독립적으로 보고하고, 그 위에 aggregate release-blocking gate를 얹는다.
- `stream_shard`, `consumer_group` 등 기존 metric semantics와 충돌하지 않게 한다.

### 비범위

- Kubernetes 전환 후 pod 장애 orchestration (후보 B로 분리).
- 네트워크 partition/latency 주입(tc/netem), CPU/메모리 압박 (후속 슬라이스).
- gateway rolling restart 연계 (후보 B).
- 실제 사용자 데이터/계정 사용 (synthetic room/계정만 사용).

## 2. 접근안

### 산출물 파일

| 파일 | 역할 |
| --- | --- |
| `docs/phase7_chaos_test_runbook.md` | 운영 runbook (시나리오, 절차, 관측 지표, release-blocking 기준, rollback) |
| `scripts/lib/phase7ChaosPlan.mjs` | 순수 로직 (인자 파싱, 시나리오 빌드, 체크 평가, summary, exit code) |
| `scripts/lib/phase7ChaosPlan.test.mjs` | 단위 테스트 (TDD 대상) |
| `scripts/phase7-chaos-runner.mjs` | 얇은 runner (docker/HTTP I/O, 폴링) |
| `docs/phase7_slices.md` | 인덱스: 후보 A를 현재 슬라이스로 이동 |

### 시나리오 카탈로그

| 시나리오 | 기본 target | 주입 | 검증 의미 |
| --- | --- | --- | --- |
| `gateway-kill` | `chat-websocket-app-1` | `docker kill` | 2-replica 중 1개 kill 시 나머지가 서빙하고 kill된 replica가 복귀한다 |
| `worker-kill` | `chat-worker-app-1` | `docker kill` | Streams consumer group 재처리와 lag 회복이 일어난다 |
| `redis-restart` | `redis` | `docker restart` | stream/admission 상태가 복구된다 |
| `replica-kill` | `postgres-replica` | `docker kill` | read 경로 degrade 후 복귀한다 |

runner는 주입 후 `--no-restore`가 아니면 `docker start`로 컨테이너를 복귀시킨 뒤 복구 폴링을 시작한다.

### Runner CLI

```bash
# 기본: dry-run (계획만 JSON 출력, docker 명령 미실행)
node scripts/phase7-chaos-runner.mjs --scenario worker-kill

# 실제 주입 + 복구 검증 + gate
node scripts/phase7-chaos-runner.mjs --scenario worker-kill --execute \
  --recovery-timeout-ms 30000 --checks health,functional,lag
```

주요 옵션: `--scenario`, `--target`, `--execute`(기본 dry-run), `--no-restore`, `--recovery-timeout-ms`, `--scenario-slo-ms`, `--checks`, `--lag-threshold`, `--pending-threshold`, `--json`. 엔드포인트는 env 기본값(`CHAT_PHASE7_BASE_URL`, `CHAT_PHASE7_METRICS_URL`).

### 복구 판정 모델

4개 체크를 각각 독립적으로 평가하고 개별 복구 여부와 소요 시간을 보고한다.

| 체크 | 방법 | 개별 pass 조건 |
| --- | --- | --- |
| `health` | 관련 app `actuator/health` 폴링 | recovery-timeout 내 `UP` 복귀 |
| `functional` | synthetic room 메시지 송신→수신 probe | recovery-timeout 내 end-to-end 성공 |
| `lag` | `actuator/prometheus`에서 `chat.redis.stream.group.lag`/`.pending` scrape | threshold 이하로 회복 |
| `recovery-slo` | required 체크 전부 pass까지의 wall-clock | scenario SLO 이내 |

aggregate `releaseBlocking = true` ⟺ required 체크 중 하나라도 fail 또는 SLO 초과. 각 체크 결과는 summary에 개별 표기하여 부분 복구(예: health만 복구, lag 미복구)를 구분한다.

### Summary JSON / exit code

```json
{
  "scenario": "worker-kill",
  "dryRun": false,
  "injectedContainer": "chat-worker-app-1",
  "totalRecoveryMs": 18200,
  "sloMs": 30000,
  "checks": [
    { "check": "health",     "required": true, "recovered": true,  "recoveryMs": 4200 },
    { "check": "functional", "required": true, "recovered": true,  "recoveryMs": 9100 },
    { "check": "lag",        "required": true, "recovered": false, "recoveryMs": null, "lastValue": 142 }
  ],
  "recoverySloMet": true,
  "releaseBlocking": true
}
```

exit code: `0` = 비blocking, `1` = release-blocking, `2` = 사용법/인자 오류. dry-run은 항상 `0`.

## 3. 복잡도

- 복구 폴링 시간 복잡도: `O(C * P)` — `C`는 체크 수, `P`는 폴링 횟수(`recovery-timeout / interval`).
- summary 집계 시간 복잡도: `O(C)`.
- 공간 복잡도: `O(C)`.

## 4. 주의사항

> - `--execute`는 실제 컨테이너를 kill/restart하므로 운영·공유 환경에서 금지하고 Compose 로컬·CI에서만 사용한다.
> - synthetic room/계정만 사용하고 실제 사용자 데이터에 접근하지 않는다.
> - Compose 전용이며 Kubernetes pod 장애는 후보 B로 분리한다.
> - `--no-restore` 사용 시 컨테이너 수동 복구가 필요하므로 runbook에 복구 절차를 명시한다.
> - metric scrape 시 session token, ticket, raw IP, message body 같은 민감값을 summary나 로그에 넣지 않는다.

## 5. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 운영 문서 + 자동 runner | 재현성과 CI 연계가 가능하고 기존 패턴과 일관된다 | docker 의존성이 있다 | 선택 |
| 외부 chaos 도구(Pumba/Toxiproxy) | 풍부한 fault 주입(network partition 등) | 의존성·학습비용이 크고 기존 스크립트 패턴과 이질적이다 | 후속 |
| runbook 문서만 작성 | 단순하다 | 수동·비재현이라 release gate 판정이 흐려진다 | 제외 |

## 6. 후속 질문

- 각 시나리오 SLO(복구 시간) 기본값을 어떤 baseline 측정으로 고정할 것인가?
- network partition/latency 주입을 다음 chaos 슬라이스로 분리할 것인가?
- chaos runner를 CI release gate에 nightly로 연결할 것인가?
