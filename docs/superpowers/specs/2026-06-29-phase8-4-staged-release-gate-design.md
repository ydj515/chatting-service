# Phase 8.4 Staged Release Gate 설계서

- 작성일: 2026-06-29
- 슬라이스: Phase 8.4 hot room release gate 단계형 판정 보강
- 상태: 설계 승인
- 기준 문서: `production-readiness-assessment-2026-06-28.html`
- 관련 기존 설계: `docs/superpowers/specs/2026-06-26-phase8-4-hot-room-shard-release-gate-design.md`

## 1. 문제 이해 / 요구사항 정리

### 조건

- Phase 8.4의 hot room shard 분산 구현과 10k release gate wrapper는 이미 존재한다.
- 2026-06-28 production readiness 평가에서 full Docker cluster 기동, Gradle 테스트, Node 테스트는 통과했다.
- 하지만 10k running-stack release gate는 공개 production 판정에 필요한 artifact를 만들지 못했다.
- 1차 실행은 WebSocket ticket 발급 단계에서 `429`로 실패했다.
- rate-limit env 보강 후 2차 실행은 `issue.success=5104`, `consume.success=5104`까지 진행한 뒤 viewer 준비 중 `fetch failed`로 실패했다.
- 실패는 메시지 본문 부하 이전에 발생했으므로 fanout p95, stream shard count, stream lag threshold까지 도달하지 못했다.
- nginx runtime 기본값은 `worker_connections 1024`로 확인되어 proxy connection budget이 병목 후보로 남아 있다.
- 기존 `scripts/load-chat.mjs`는 viewer별 수신 메시지를 배열에 저장하므로 대규모 gate에서 load generator 자체 메모리 사용량도 병목 후보가 될 수 있다.

### 목표

- release gate를 `1k`, `5k`, `10k` 단계로 실행해 어느 규모까지 안정적으로 통과하는지 확인한다.
- 기본 단계는 `1k -> 5k -> 10k` 순서로 실행한다.
- 각 단계는 viewer 수와 messages/sec를 같은 값으로 둔다.
- 기본 duration은 기존 10k gate와 동일하게 `60s`로 유지한다.
- 어떤 단계에서 실패했는지 JSON artifact에 남긴다.
- 실패가 발생해도 마지막 통과 단계, 실패 단계, 실패 원인, 실패 stage의 load runner stderr를 확인할 수 있게 한다.
- 기본 동작은 fail-fast로 한다. 예를 들어 `5k`에서 실패하면 `10k`는 실행하지 않는다.
- 필요하면 `--stages 1000,3000,5000,7000,10000`처럼 단계 목록을 커스터마이즈할 수 있게 한다.
- 기존 단일 stage 실행도 유지한다. `--single-stage --viewers 10000 --messages-per-sec 10000` 방식과 호환된다.

### 비범위

- nginx TLS/wss, 운영 secret manager, HMAC key rotation은 이 설계의 범위가 아니다.
- Kubernetes, HPA, PDB, DR, backup/restore runbook은 Phase 9 범위다.
- 분산 load generator cluster는 이번 변경에 포함하지 않는다.
- service capacity 자체를 보장하는 작업이 아니라, capacity를 단계별로 측정하고 병목을 식별하기 쉽게 만드는 작업이다.
- Redis node kill, MinIO upload failure, moderation smoke, heartbeat no-pong soak는 별도 장애 리허설 범위다.

## 2. 해결 접근

### 선택한 접근

기본 gate를 `1k`, `5k`, `10k` 세 단계로 확장한다.

| Stage | Viewers | Messages/sec | Duration | 목적 |
| --- | ---: | ---: | ---: | --- |
| `1k` | `1000` | `1000` | `60s` | running-stack 기본 안정성 확인 |
| `5k` | `5000` | `5000` | `60s` | 기존 실패 지점 근처의 준비 단계와 fanout 여력 확인 |
| `10k` | `10000` | `10000` | `60s` | 공개 production rehearsal 기준 최종 gate |

`scripts/phase8-hot-room-release-gate.mjs`는 단계별로 기존 load runner와 Prometheus snapshot 판정을 실행한다. 각 stage는 독립적인 hot room을 만들고, stage별 load summary와 Prometheus snapshot을 결과 JSON에 담는다.

기본 실행은 다음 의미를 가진다.

```bash
CHAT_PROMETHEUS_URL=http://localhost:9090 node scripts/phase8-hot-room-release-gate.mjs
```

- `1k` stage 실행
- `1k`가 통과하면 `5k` stage 실행
- `5k`가 통과하면 `10k` stage 실행
- 실패 시 이후 stage는 생략
- 최종 JSON에 `ok`, `lastPassedStage`, `failedStage`, `stages`를 기록
- 실패가 있으면 exit code `1`, 전체 통과면 exit code `0`

### 이유

- 10k 단일 gate는 실패했을 때 "아예 1k도 불안정한지", "5k까지는 안정적인지", "10k 직전에서 무너지는지"를 알기 어렵다.
- 단계형 gate는 capacity envelope를 빠르게 좁힌다.
- `1k`는 기능/구성 회귀를 빨리 잡고, `5k`는 기존 5,104 ticket 진행 지점 주변을 다시 검증한다.
- `10k`는 기존 production rehearsal 기준을 유지하므로 최종 판정 의미가 약해지지 않는다.
- fail-fast는 실패한 환경에서 불필요하게 더 큰 부하를 걸지 않아 원인 조사 artifact가 흐려지는 것을 줄인다.
- 커스텀 stage 옵션을 열어두면 기본 gate는 단순하게 유지하면서도 병목 구간을 더 촘촘히 탐색할 수 있다.

## 3. 설계 상세

### Stage model

stage는 release gate 실행 단위다.

```js
{
  name: '5k',
  viewers: 5000,
  messagesPerSec: 5000,
  durationSeconds: 60
}
```

기본 stage 이름은 viewer 수가 1000 단위로 나누어떨어지면 `${viewers / 1000}k`를 사용한다. 그렇지 않으면 `${viewers}`를 문자열로 사용한다.

`--stages`는 comma-separated positive integer list를 받는다.

```bash
node scripts/phase8-hot-room-release-gate.mjs --stages 1000,3000,5000,7000,10000
```

각 stage의 `messagesPerSec`는 기본적으로 `viewers`와 같은 값이다. 필요하면 기존 옵션 `--messages-per-sec`를 단일 stage 실행에만 사용한다. `--stages`와 `--messages-per-sec`를 함께 쓰면 의미가 모호하므로 에러로 처리한다.

### CLI contract

지원하는 주요 실행 모드는 두 가지다.

#### Staged mode

기본 모드다.

```bash
node scripts/phase8-hot-room-release-gate.mjs
node scripts/phase8-hot-room-release-gate.mjs --stages 1000,5000,10000
```

이 모드에서는 `--viewers`와 `--messages-per-sec`를 stage 목록과 함께 쓰지 않는다. stage마다 viewer 수와 messages/sec가 동일하게 적용된다.

#### Single-stage compatibility mode

기존 단일 gate 실행을 유지한다.

```bash
node scripts/phase8-hot-room-release-gate.mjs --single-stage --viewers 10000 --messages-per-sec 10000
```

`--single-stage`를 명시하면 기존 `--viewers`, `--messages-per-sec`, `--duration` 조합을 사용한다. 이 모드는 기존 자동화나 수동 검증 스크립트가 깨지지 않도록 둔다.

### Result JSON

성공 예시는 다음 형태다.

```json
{
  "ok": true,
  "mode": "staged",
  "lastPassedStage": "10k",
  "failedStage": null,
  "stages": [
    {
      "name": "1k",
      "ok": true,
      "options": {
        "viewers": 1000,
        "messagesPerSec": 1000,
        "durationSeconds": 60
      },
      "loadSummary": {},
      "prometheusSnapshot": {}
    },
    {
      "name": "5k",
      "ok": true,
      "options": {
        "viewers": 5000,
        "messagesPerSec": 5000,
        "durationSeconds": 60
      },
      "loadSummary": {},
      "prometheusSnapshot": {}
    },
    {
      "name": "10k",
      "ok": true,
      "options": {
        "viewers": 10000,
        "messagesPerSec": 10000,
        "durationSeconds": 60
      },
      "loadSummary": {},
      "prometheusSnapshot": {}
    }
  ],
  "thresholds": {
    "minStreamShardCount": 16,
    "maxFanoutP95Ms": 500,
    "maxStreamGroupLagEntries": 1000
  }
}
```

실패 예시는 다음 형태다.

```json
{
  "ok": false,
  "mode": "staged",
  "lastPassedStage": "1k",
  "failedStage": "5k",
  "stages": [
    {
      "name": "1k",
      "ok": true,
      "loadSummary": {},
      "prometheusSnapshot": {}
    },
    {
      "name": "5k",
      "ok": false,
      "error": {
        "stage": "5k",
        "message": "load runner failed with code 1",
        "stderr": "viewer 4200 issue-ticket failed: fetch failed"
      }
    }
  ]
}
```

stdout에는 성공/실패와 무관하게 JSON을 출력한다. exit code는 전체 성공 시 `0`, 하나라도 실패하면 `1`이다. 이렇게 해야 shell redirection으로 artifact를 항상 저장할 수 있다.

### Load runner diagnostics

`fetch failed`만으로는 병목 계층을 좁히기 어렵다. `scripts/load-chat.mjs`는 HTTP/WebSocket 준비 단계에서 다음 정보를 포함한 에러 메시지를 만들어야 한다.

- stage 또는 caller label
- viewer index
- step 이름
  - `register-sender`
  - `login-sender`
  - `create-room`
  - `register-viewer`
  - `login-viewer`
  - `join-room`
  - `issue-ticket`
  - `websocket-handshake`
  - `connect-sender`
- method
- redacted URL
- HTTP status와 response body 일부
- fetch/network error message

예시는 다음과 같다.

```text
viewer 5104 issue-ticket POST http://localhost/api/ws-tickets failed: fetch failed
```

WebSocket URL의 `ticket`이나 session token은 artifact에 남기지 않는다.

### Memory profile

Phase 8.4 staged gate는 raw `roomSeq` ordering을 강제하지 않는다. 따라서 대규모 stage에서는 viewer별 모든 메시지를 저장하지 않고 count 중심으로 집계하는 lightweight mode가 필요하다.

기본 staged gate는 다음만 보관한다.

- viewer별 received count
- viewer별 첫 번째/마지막 sample 소량
- JSON parse 실패 수
- WebSocket close/error 수

`--assert-room-seq-order`나 takeover diagnostic처럼 원문 메시지 배열이 필요한 모드는 기존 저장 방식을 사용할 수 있다. staged gate 기본값은 count-only mode다.

### Prometheus snapshot

각 stage가 끝난 뒤 기존 threshold를 그대로 판정한다.

| Metric | 기준 |
| --- | --- |
| fanout p95 | `500ms` 이하 |
| observed stream shard count | `16` 이상 |
| Redis Streams group lag | `1000` entries 이하 |

stage별 snapshot을 별도로 남긴다. 1k가 통과하고 5k가 실패하면 1k snapshot은 성공 artifact로 보존되고, 5k는 실패 원인을 남긴다.

### Rate-limit and connection budget

staged gate를 실행할 때 WebSocket ticket rate-limit은 최대 stage viewer 수 이상이어야 한다.

```bash
CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_PER_IP=20000
CHAT_AUTH_WEB_SOCKET_TICKET_RATE_LIMIT_PER_USER=20
```

nginx와 app connection budget은 staged gate를 통과할 수 있는 값으로 명시해야 한다. 이 설계의 구현 계획에서는 다음을 함께 검토한다.

- nginx `worker_connections`
- nginx container `nofile` ulimit
- Tomcat `SERVER_TOMCAT_MAX_CONNECTIONS`
- load generator process file descriptor limit

## 4. 실패 판정

stage는 다음 중 하나라도 발생하면 실패다.

- load runner process exit code가 `0`이 아니다.
- load runner stdout을 JSON으로 파싱할 수 없다.
- load summary의 `ok`가 `true`가 아니다.
- sent count가 `messagesPerSec * durationSeconds`보다 작다.
- viewer 수 또는 received count 배열 길이가 stage viewer 수와 다르다.
- viewer별 received count가 `minReceivedRatio` 기준보다 작다.
- Prometheus query가 실패한다.
- fanout p95, stream shard count, stream lag threshold 중 하나라도 기준을 넘는다.

실패한 stage 이후의 stage는 실행하지 않는다. 결과 JSON에는 생략된 stage를 넣지 않는다.

## 5. 복잡도

- 전체 staged gate 시간 복잡도: `O(sum(Vi + Mi + Qi))`
  - `Vi`는 stage `i`의 viewer 수다.
  - `Mi`는 stage `i`의 전송 메시지 수다.
  - `Qi`는 stage `i`의 Prometheus query 수다.
- 기본 `1k/5k/10k` 총 viewer 준비 작업은 `O(16000)`이다.
- 기본 `1k/5k/10k` 총 메시지 전송 목표는 `O(960000)`이다.
- count-only load summary 공간 복잡도: `O(Vi + E + S)`
  - `E`는 stage error 수다.
  - `S`는 제한된 sample 수다.
- 기존 full message retention 모드 공간 복잡도: 최악 `O(Vi * Mi)`에 가까워질 수 있으므로 staged gate 기본값으로 사용하지 않는다.

## 6. 주의사항

> - staged gate는 production readiness의 성능/관측 근거를 개선하지만 TLS/wss, secret manager, K8s/HPA/DR Blocker를 해소하지 않는다.
> - 5k 실패가 곧 서비스 한계라는 뜻은 아니다. nginx, app, DB, Redis, load generator 중 어느 계층인지 artifact로 분리해야 한다.
> - 단일 로컬 머신에서 10k load를 만들면 load generator가 먼저 병목이 될 수 있다.
> - `1k` stage 통과만으로 공개 production을 허용하면 안 된다. 최종 공개 SLA 판단은 `10k` stage와 별도 보안/배포 리스크까지 함께 봐야 한다.
> - stage별 room이 독립적으로 만들어지므로 이전 stage의 데이터가 다음 stage의 DB/Redis 상태에 영향을 줄 수 있다. 반복 실행 전 clean infra 또는 test data cleanup 전략을 문서화해야 한다.

## 7. 대안

| 대안 | 장점 | 단점 | 판단 |
| --- | --- | --- | --- |
| `1k/5k/10k` 고정 단계 | 단순하고 보고서와 release artifact에 설명하기 쉽다 | 5k와 10k 사이의 정확한 한계는 추가 실행이 필요하다 | 선택 |
| `1k/3k/5k/7k/10k` 세밀 단계 | 병목 구간을 더 촘촘히 찾을 수 있다 | 실행 시간이 길고 환경 피로도가 커진다 | 커스텀 stage로 지원 |
| binary search 방식 | 최대 처리 가능 지점을 빠르게 추정할 수 있다 | release gate로는 설명력이 낮고 반복 실행 상태 관리가 복잡하다 | 제외 |
| 10k 단일 gate 유지 | 기존 기준이 단순하다 | 실패 시 capacity envelope를 알기 어렵다 | 제외 |
| 분산 load generator 먼저 구축 | 10k 부하 발생 신뢰도가 높아진다 | 이번 변경보다 큰 별도 인프라 작업이 된다 | 후속 |

## 8. 후속 질문

- 5k와 10k 사이에서 실패하면 기본 커스텀 stage를 `7000` 또는 `8000` 중 어디부터 추가할 것인가?
- staged gate artifact를 CI에서 장기 보관할지, 로컬 `.artifacts/phase8-gate`에만 둘 것인가?
- count-only mode에서 보관할 per-viewer sample 개수를 몇 개로 제한할 것인가?
