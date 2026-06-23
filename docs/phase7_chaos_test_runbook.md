# Phase 7 Chaos Test Runbook

이 문서는 Phase 7에서 Compose 환경 핵심 컴포넌트(Gateway/Worker/Redis/replica) 장애를 주입하고 복구를 검증하는 chaos test 절차와 release-blocking 기준을 정의한다.

## 0. 실행 가능한 Chaos Runner

이번 슬라이스는 장애 주입과 복구 검증을 자동화하는 runner를 제공한다. 기본은 dry-run이며 실제 주입은 `--execute`에서만 일어난다.

```bash
# dry-run: 실제 docker 명령 없이 주입 계획만 출력한다.
node scripts/phase7-chaos-runner.mjs --scenario worker-kill

# 실제 주입 + 복구 검증 + release gate
node scripts/phase7-chaos-runner.mjs --scenario worker-kill --execute \
  --recovery-timeout-ms 30000 --checks health,functional,lag
```

환경 변수:

```text
CHAT_PHASE7_BASE_URL=http://localhost
CHAT_PHASE7_METRICS_URL=http://localhost/api/actuator/prometheus
CHAT_HTTP_URL=http://localhost/api
CHAT_WS_URL=ws://localhost/api/ws/chat
CHAT_PHASE7_CHAOS_RECOVERY_TIMEOUT_MS=30000
CHAT_PHASE7_CHAOS_POLL_INTERVAL_MS=1000
```

주요 옵션:

| 옵션 | 기본값 | 의미 |
| --- | --- | --- |
| `--scenario` | (필수) | `gateway-kill`, `worker-kill`, `redis-restart`, `replica-kill` 중 하나 |
| `--target` | 시나리오 기본 컨테이너 | 주입 대상 Compose 서비스 오버라이드 |
| `--execute` | (off) | 실제 docker kill/restart 실행. 미지정 시 dry-run |
| `--no-restore` | (off) | kill 시나리오에서 컨테이너 자동 복구(`docker start`)를 건너뜀 |
| `--recovery-timeout-ms` | `30000` | 복구 폴링 최대 시간 |
| `--scenario-slo-ms` | 시나리오 기본 SLO | 복구 시간 SLO 오버라이드 |
| `--checks` | `health,functional,lag` | 실행할 복구 체크 목록 |
| `--lag-threshold` | `0` | lag gauge 복구 판정 임계값 |
| `--pending-threshold` | `0` | pending gauge 복구 판정 임계값 |
| `--json` | (off) | summary를 한 줄 JSON으로 출력 |

> `--execute`는 실제 컨테이너를 kill/restart한다. 운영·공유 환경에서는 금지하고 Compose 로컬·CI에서만 사용한다.

## 1. 시나리오 카탈로그

| 시나리오 | 기본 target | 주입 | 검증 의미 |
| --- | --- | --- | --- |
| `gateway-kill` | `chat-websocket-app-1` | `docker kill` | 2-replica 중 1개 kill 시 나머지가 서빙하고 kill된 replica가 복귀한다 |
| `worker-kill` | `chat-worker-app-1` | `docker kill` | Streams consumer group 재처리와 lag 회복이 일어난다 |
| `redis-restart` | `redis` | `docker restart` | stream/admission 상태가 복구된다 |
| `replica-kill` | `postgres-replica` | `docker kill` | read 경로 degrade 후 복귀한다 |

`kill` 시나리오는 `--no-restore`가 아니면 주입 후 `docker start`로 컨테이너를 복귀시킨다. `restart`는 컨테이너를 그대로 되살리므로 별도 복구가 필요 없다.

## 2. 수동 절차 (runner 없이)

runner가 없거나 단계를 직접 관찰하려면 다음 순서로 진행한다.

1. 주입 전 baseline 확인: `curl -f http://localhost/api/actuator/health`가 `UP`인지, lag/pending gauge가 정상인지 본다.
2. 대상 컨테이너 id 조회: `docker compose ps -q chat-worker-app-1`.
3. 장애 주입: `docker kill <id>` (또는 redis는 `docker restart redis`).
4. (kill 시나리오) 복구: `docker start <id>`.
5. 복구 관측: health 엔드포인트 `UP` 복귀, synthetic 메시지 송수신 성공, lag/pending gauge가 threshold 이하로 회복하는지 확인한다.
6. 각 체크의 복구 여부와 복구 시간을 기록한다.

## 3. 복구 판정 (release-blocking 기준)

복구는 체크별로 독립적으로 판정하고 개별 복구 여부를 기록한다. 예를 들어 health만 복구되고 lag는 미복구일 수 있으며, summary는 이를 구분해 보고한다.

| 체크 | 방법 | 개별 pass 조건 |
| --- | --- | --- |
| `health` | `actuator/health` 폴링 | recovery-timeout 내 `UP` 복귀 |
| `functional` | synthetic room 메시지 송신→수신 probe (`verify-chat.mjs`) | recovery-timeout 내 end-to-end 성공 |
| `lag` | `actuator/prometheus`에서 `chat.redis.stream.group.lag`/`.pending` scrape | threshold 이하로 회복 |
| recovery SLO | required 체크 전부 복구까지의 wall-clock | scenario SLO 이내 |

release-blocking 판정:

- required 체크 중 하나라도 복구 실패 → release-blocking.
- required 체크가 전부 복구해도 복구 시간이 scenario SLO를 초과 → release-blocking.
- dry-run은 항상 비blocking이다.

exit code: `0` = 비blocking, `1` = release-blocking, `2` = 사용법/인자 오류.

### Summary 예시

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

## 4. Rollback / 복구

- `--no-restore`로 kill한 경우 수동 복구: `docker compose up -d <service>` 또는 `docker start <id>`.
- redis/replica 복구 후에도 lag/pending gauge가 threshold로 돌아오지 않으면 release를 막고 worker/consumer group 상태를 조사한다.
- 주입 중 다른 고주파 작업(`pollWriter`, `pollFanout` 등)에 영향이 없는지 함께 관측한다.

## 5. 복잡도

- 복구 폴링 시간 복잡도: `O(C * P)` (`C`는 체크 수, `P`는 폴링 횟수)
- summary 집계 시간 복잡도: `O(C)`
- 공간 복잡도: `O(C)`

## 6. 주의사항

> - `--execute`는 실제 컨테이너를 kill/restart하므로 운영·공유 환경에서 금지한다. Compose 로컬·CI 한정.
> - synthetic room/계정만 사용하고 실제 사용자 데이터에 접근하지 않는다.
> - Compose 전용이며 Kubernetes pod 장애 orchestration은 별도 슬라이스(후보 B)로 분리한다.
> - `--no-restore` 사용 시 컨테이너 수동 복구가 필요하다.
> - summary나 로그에 session token, ticket, raw IP, message body 같은 민감값을 넣지 않는다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| 운영 문서 + 자동 runner | 재현성과 CI 연계가 가능하고 기존 패턴과 일관된다 | docker 의존성이 있다 | 선택 |
| 외부 chaos 도구(Pumba/Toxiproxy) | network partition 등 풍부한 fault | 의존성·학습비용이 크고 기존 패턴과 이질적이다 | 후속 |
| runbook 문서만 작성 | 단순하다 | 수동·비재현이라 release gate 판정이 흐려진다 | 제외 |

## 8. 후속 질문

- 각 시나리오 SLO 기본값을 어떤 baseline 측정으로 고정할 것인가?
- network partition/latency 주입을 다음 chaos 슬라이스로 분리할 것인가?
- chaos runner를 CI release gate에 nightly로 연결할 것인가?
