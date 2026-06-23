# Phase 7 WebSocket Reconnect Chaos Orchestration 구현 계획

설계: [2026-06-24-phase7-websocket-reconnect-chaos-orchestration-design.md](../specs/2026-06-24-phase7-websocket-reconnect-chaos-orchestration-design.md)

순수 로직은 TDD로 구현하고, docker/HTTP I/O는 얇은 runner에 둔다. 기존 chaos runner / takeover smoke 패턴을 따른다.

## 1. 순수 로직: `scripts/lib/phase7ReconnectChaosPlan.mjs` (TDD)

먼저 `phase7ReconnectChaosPlan.test.mjs`를 RED로 작성한 뒤 구현한다.

export 함수:

- `parseReconnectChaosArgs(argv, env)` — orchestration 옵션 + reconnect pass-through 파싱.
  - 필수: `--fault <gateway-rolling-restart|gateway-hard-kill>`.
  - orchestration: `--gateways`(csv), `--execute`, `--no-restore`, `--rolling-step-delay-ms`, `--inject-after-ms`, `--ready-timeout-ms`, `--json`.
  - reconnect pass-through(chaos 기본값): `--clients`(5), `--reconnects-per-client`(6), `--attempt-spacing-ms`(1000), `--jitter-ms`(250), `--cohort`(synthetic), `--reason`(모드 기본), `--scenario`(모드 라벨).
  - gate 비율 4종(`--min-ticket-issue-success-ratio` 등)은 지정 시에만 보관.
  - cohort/reason은 reconnect-load의 enum을 import해 검증. 미지정 시 모드별 기본 적용.
- `buildGatewayFaultPlan(options)` — 순서 있는 step 배열 반환.
  - rolling-restart: `[{ action: 'restart', service }, ...]`, `restoreNeeded: false`.
  - hard-kill: `[{ action: 'kill', service, restoreNeeded: options.restore }]`.
- `buildReconnectLoadArgs(options)` — child argv 배열. `--scenario`, `--reason`, `--clients`, `--reconnects-per-client`, `--cohort`, `--attempt-spacing-ms`, `--jitter-ms` 항상 포함. gate 비율은 지정된 것만 추가.
- `summarizeReconnectChaos({ faultMode, injectedContainers, injectionOffsetMs, reconnectSummary, dryRun })` — fault 메타데이터 + reconnect 결과 병합, `releaseBlocking = dryRun ? false : !(reconnectSummary?.ok === true)`, `failedGates` top-level 노출.
- `exitCodeForReconnectChaosSummary(summary)` — dry-run/non-blocking `0`, blocking `1`.
- `export const KNOWN_FAULT_MODES`.

테스트 케이스(주요):

- fault 모드 필수/미지정 throw, 알 수 없는 모드 throw.
- 모드별 기본 gateways / reason / scenario.
- `--gateways` override, `--no-restore` 반영.
- rolling-restart는 restart step만, hard-kill은 kill step + restoreNeeded.
- `buildReconnectLoadArgs`가 기본 + override 인자를 정확히 생성.
- cohort/reason enum 검증 throw.
- `summarizeReconnectChaos`: ok storm → non-blocking, failed storm → blocking, dry-run → 항상 non-blocking.
- `exitCodeForReconnectChaosSummary` 0/1.

## 2. reconnect-load `--ready-file` 추가

- `scripts/lib/phase7ReconnectLoadPlan.mjs`
  - `parseReconnectLoadArgs`에 `--ready-file` 추가 → `options.readyFile`(기본 null).
  - cohort/reason enum을 named export(`RECONNECT_COHORTS`, `RECONNECT_REASONS`).
  - 테스트: `--ready-file` 파싱, 미지정 시 null.
- `scripts/phase7-reconnect-load.mjs`
  - 등록 루프 직후, attempt `Promise.all` 직전에 `options.readyFile`가 있으면 `{ startedAt, clients }` JSON 기록.

## 3. runner: `scripts/phase7-reconnect-chaos.mjs`

- 인자 파싱 실패 → exit 2.
- dry-run(기본): fault plan(서비스 placeholder) + reconnect args + dry-run summary 출력, exit 0. 라이브 스택 불필요.
- `--execute`:
  1. 각 gateway 서비스 → `docker compose ps -q` 로 container id resolve.
  2. temp ready-file 경로 생성, reconnect-load child spawn(`--ready-file` 포함), stdout 캡처/ stderr inherit.
  3. ready-file 폴링(`--ready-timeout-ms`), 신호 후 `--inject-after-ms` 대기, `injectionOffsetMs` 기록.
  4. fault step 순차 실행(restart/kill), rolling step 사이 `--rolling-step-delay-ms` sleep.
  5. child 종료 대기, stdout에서 마지막 JSON summary 파싱.
  6. `summarizeReconnectChaos`로 병합 후 출력, exit code 설정.
  7. finally: kill한 컨테이너 `docker start` 복구(`--no-restore` 아니면), ready-file 정리.
- 민감값 redact: reconnect-load summary를 그대로 싣되 추가 가공 없음.

## 4. 문서

- `docs/phase7_reconnect_chaos_orchestration.md`: 실행 예시, 옵션 표, fault 카탈로그, 타이밍 설명, release-blocking 기준, rollback, 복잡도, 주의사항, 대안, 후속 질문.
- `docs/phase7_slices.md`: 후보 B를 현재 슬라이스 9(구현 완료)로 이동하고 문서 링크 추가.

## 5. 검증

- `node --test scripts/lib/phase7ReconnectChaosPlan.test.mjs scripts/lib/phase7ReconnectLoadPlan.test.mjs` 그린.
- 전체 `node --test scripts/` 회귀 그린.
- dry-run 스모크: `node scripts/phase7-reconnect-chaos.mjs --fault gateway-rolling-restart` / `--fault gateway-hard-kill` 가 plan JSON과 exit 0을 낸다.
